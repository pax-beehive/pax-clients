import Foundation
import Observation

struct DirectoryEntry: Decodable, Identifiable, Sendable {
  let name: String
  let path: String
  var id: String { path }
}
struct DirectoryPage: Decodable, Sendable {
  let home: String
  let path: String
  let parent: String?
  var entries: [DirectoryEntry]
  let next_after: String?
}
struct PendingUpgrade: Codable, Sendable {
  let commandID: String
  let version: String
}
struct UpgradeProgress {
  let message: String
  var terminal = false
  var succeeded = false
  init(_ value: JSON) {
    let confirmation = value["maintenance_confirmation"]["status"].string
    if confirmation == "confirmed" {
      message = "Upgrade complete"
      terminal = true
      succeeded = true
      return
    }
    if confirmation == "version_mismatch" {
      message = "Device reconnected with a different version. Check the device before retrying."
      terminal = true
      return
    }
    let command = value["command"] == .null ? value["command_ack"] : value["command"]
    if ["failed", "rejected"].contains(command["status"].string) {
      message = command.text("error_message", command["error"].text("message", "Upgrade failed"))
      terminal = true
      return
    }
    switch command["result"]["phase"].string {
    case "pending_idle", "draining": message = "Waiting for active work to finish"
    case "staging", "staged": message = "Downloading and verifying update"
    case "activating", "shutdown_committed": message = "Waiting for the device to reconnect"
    default:
      message =
        confirmation == "awaiting_running" ? "Waiting for paxd to start" : "Checking upgrade status"
    }
  }
}
extension WorkspaceRepository {
  func directories(node: String, path: String = "", after: String = "", hidden: Bool = false)
    async throws -> DirectoryPage
  {
    let result = try await get(
      ["nodes", node, "daemon", "directories"],
      query: ["path": path, "after": after, "show_hidden": String(hidden)])
    try require(
      result["error"] == .null,
      result["error"].text("message", "Directory browsing requires a newer paxd"))
    try require(
      result["directories"] != .null,
      "Directory browsing is unavailable. Update paxd and try again.")
    return try JSONDecoder().decode(DirectoryPage.self, from: result["directories"].data())
  }
  func latestPaxd(_ node: Resource) async throws -> String {
    let os = node.raw["os"].string
    let arch = node.raw["arch"].string
    try require(!os.isEmpty && !arch.isEmpty, "This device has not reported its platform yet")
    let token = try await auth.accessToken()
    let result = try await api.json(
      PaxAPI.request(
        path: ["public", "paxd", "download"], token: token,
        query: ["platform": "\(os)/\(arch)", "tags": "stable"]))
    try require(!result["version"].string.isEmpty, "No stable release is available for this device")
    return result["version"].string
  }
}

@MainActor @Observable final class DirectoryBrowser {
  let node: String
  let repo: WorkspaceRepository
  var page: DirectoryPage?
  var hidden = false
  var loading = false
  var error: String?
  private var requestedPath = ""
  private var requestedAfter = ""
  private var task: Task<Void, Never>?
  init(node: String, repo: WorkspaceRepository) {
    self.node = node
    self.repo = repo
  }
  func cancel() { task?.cancel() }
  func retry() { load(requestedPath, after: requestedAfter) }
  func load(_ path: String = "", after: String = "") {
    task?.cancel()
    requestedPath = path
    requestedAfter = after
    loading = true
    error = nil
    task = Task {
      do {
        var next = try await repo.directories(node: node, path: path, after: after, hidden: hidden)
        try Task.checkCancellation()
        if !after.isEmpty { next.entries = (page?.entries ?? []) + next.entries }
        page = next
        loading = false
      } catch {
        if !Task.isCancelled {
          self.error = error.localizedDescription
          loading = false
        }
      }
    }
  }
}

@MainActor @Observable final class NodeToolsModel {
  var node: Resource
  let repo: WorkspaceRepository
  var latest = ""
  var pending: PendingUpgrade?
  var loading = false
  var message = ""
  var error: String?
  private var ownerKey = ""
  private var task: Task<Void, Never>?
  init(node: Resource, repo: WorkspaceRepository) {
    self.node = node
    self.repo = repo
  }
  func cancel() { task?.cancel() }
  func load() {
    task?.cancel()
    loading = true
    error = nil
    task = Task {
      do {
        let vault = try repo.auth.vault.read()
        try require(vault.account != nil, "Sign in to continue")
        ownerKey = vault.account!.userID + ":" + node.id
        pending = vault.pendingUpgrades?[ownerKey]
        if let pending {
          await poll(pending)
          return
        }
        let fresh = try await repo.get(["nodes", node.id])
        try Task.checkCancellation()
        node = Resource(raw: fresh, kind: "node")
        latest = try await repo.latestPaxd(node)
        loading = false
      } catch {
        if !Task.isCancelled {
          self.error = error.localizedDescription
          loading = false
        }
      }
    }
  }
  func upgrade() {
    guard !loading, pending == nil, !latest.isEmpty, node.raw["online"].bool else { return }
    let command = PendingUpgrade(
      commandID: "upgrade_" + UUID().uuidString.lowercased(), version: latest)
    pending = command
    loading = true
    error = nil
    message = "Requesting upgrade"
    task = Task {
      do {
        var vault = try repo.auth.vault.read()
        var commands = vault.pendingUpgrades ?? [:]
        commands[ownerKey] = command
        vault.pendingUpgrades = commands
        do { try repo.auth.vault.save(vault) } catch {
          pending = nil
          loading = false
          self.error = "Could not save upgrade request. Nothing was sent."
          return
        }
        try Task.checkCancellation()
        let ack = try await repo.post(
          ["nodes", node.id, "daemon", "upgrade"],
          .fields([
            "command_id": command.commandID, "version": command.version, "tag": "stable",
            "mode": "when_idle",
          ]))
        let progress = UpgradeProgress(ack)
        if progress.terminal {
          try finish(progress)
          return
        }
      } catch {
        if Task.isCancelled { return }
        if let failure = error as? ClientFailure,
          (400..<500).contains(failure.status), failure.status != 408, failure.status != 429
        {
          let rejected = JSON.object([
            "command_ack": .object([
              "status": .string("rejected"),
              "error": .fields(["message": failure.localizedDescription]),
            ])
          ])
          do { try finish(UpgradeProgress(rejected)) } catch {
            self.error = error.localizedDescription
            loading = false
          }
          return
        }
        message = "Request outcome unknown. Checking the same upgrade."
      }
      await poll(command)
    }
  }
  func check() {
    guard let pending else { return }
    task?.cancel()
    task = Task { await poll(pending) }
  }
  private func finish(_ progress: UpgradeProgress) throws {
    var vault = try repo.auth.vault.read()
    vault.pendingUpgrades?.removeValue(forKey: ownerKey)
    try repo.auth.vault.save(vault)
    if progress.succeeded {
      var raw = node.raw.object
      raw["paxd_version"] = .string(pending?.version ?? "")
      node = Resource(raw: .object(raw), kind: "node")
    }
    pending = nil
    loading = false
    message = progress.message
    error = nil
  }
  private func poll(_ command: PendingUpgrade) async {
    loading = true
    error = nil
    for _ in 0..<120 {
      do {
        let result = try await repo.get(["nodes", node.id, "daemon", "commands", command.commandID])
        try Task.checkCancellation()
        let progress = UpgradeProgress(result)
        if progress.terminal {
          try finish(progress)
          return
        }
        message = progress.message
        error =
          result["error"] == .null ? nil : result["error"].text("message", "Status unavailable")
      } catch {
        if Task.isCancelled { return }
        message = "Waiting for the device to reconnect"
      }
      do { try await Task.sleep(for: .seconds(3)) } catch { return }
    }
    loading = false
    message = "Upgrade is not confirmed yet. Check status again; no new request will be sent."
  }
}
