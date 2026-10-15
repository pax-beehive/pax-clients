import Foundation
import Observation

@MainActor @Observable final class WorkspaceModel {
  let isPreview: Bool
  let auth: UserAuth
  let repo: WorkspaceRepository
  let pairing: DevicePairing
  var account: Account?
  var workspace = Workspace()
  var restoring = true
  var needsRegion = false
  var loading = false
  private(set) var refreshing = false
  private var completingLogin = false
  private var pendingCallback: URL?
  var error: String?
  var runtime: SessionRuntime?
  var draft = ""
  private var drafts: [String: String] = [:]
  private var page = 1
  private var generation = 0
  init(api: any APITransport = PaxAPI(), vault: (any Vault)? = nil, preview: Bool = false) {
    isPreview = preview
    let vault = vault ?? SecureVault()
    auth = UserAuth(api: api, vault: vault)
    repo = WorkspaceRepository(auth: auth, api: api)
    pairing = DevicePairing(repo: repo, vault: vault)
  }
  func restore() async {
    restoring = true
    defer { restoring = false }
    do {
      account = try await auth.restore()
      needsRegion = try account == nil && auth.vault.read().session != nil
      if account != nil { await refresh() }
    } catch { self.error = error.localizedDescription }
    restoring = false
    if let callback = pendingCallback {
      pendingCallback = nil
      await completeLogin(callback)
    }
  }
  func receiveCallback(_ url: URL) async {
    guard url.scheme == "net.paxtech.workspace", url.host == "auth", url.path == "/callback" else {
      return
    }
    if restoring {
      pendingCallback = url
      return
    }
    await completeLogin(url)
  }
  func completeLogin(_ callback: URL) async {
    // ASWebAuthenticationSession and onOpenURL may deliver the same callback.
    guard !completingLogin else { return }
    guard (try? auth.vault.read().oauth) != nil else { return }
    completingLogin = true
    loading = true
    error = nil
    defer {
      loading = false
      completingLogin = false
    }
    do {
      account = try await auth.finish(callback)
      needsRegion = account == nil
      if account != nil { await refresh() }
    } catch { self.error = error.localizedDescription }
  }
  func chooseRegion(_ region: String) async {
    guard !loading else { return }
    loading = true
    defer { loading = false }
    do {
      account = try await auth.bootstrap(region: region)
      needsRegion = account == nil
      if account != nil { await refresh() }
    } catch { self.error = error.localizedDescription }
  }
  func refresh(more: Bool = false) async {
    guard !refreshing else { return }
    refreshing = true
    defer { refreshing = false }
    let epoch = generation
    do {
      let nextPage = more ? page + 1 : page
      var next = try await repo.load(page: more ? nextPage : 1)
      if more {
        let ids = Set(workspace.sessions.map(\.id))
        next.sessions = workspace.sessions + next.sessions.filter { !ids.contains($0.id) }
      } else if nextPage > 1 {
        // Polling preserves the loaded window instead of collapsing it to page one.
        for number in 2...nextPage {
          let batch = try await repo.sessionPage(number)
          let ids = Set(next.sessions.map(\.id))
          next.sessions += batch.sessions.filter { !ids.contains($0.id) }
          next.hasMore = batch.hasMore
        }
      }
      guard epoch == generation else { return }
      workspace = next
      page = nextPage
      error = nil
    } catch { if epoch == generation { self.error = error.localizedDescription } }
  }
  func select(_ session: Session?) {
    if runtime?.session.id == session?.id { return }
    if let runtime {
      drafts[runtime.session.id] = draft
      runtime.pause()
    }
    runtime = session.map { SessionRuntime(session: $0, repo: repo, pairing: pairing) }
    draft = session.flatMap { drafts[$0.id] } ?? ""
    runtime?.connect()
  }
  func signOut() async {
    guard !loading else { return }
    loading = true
    defer { loading = false }
    do {
      runtime?.pause()
      try await auth.signOut()
      generation += 1
      page = 1
      pendingCallback = nil
      select(nil)
      account = nil
      workspace = Workspace()
      needsRegion = false
      drafts = [:]
      draft = ""
      error = nil
    } catch { self.error = error.localizedDescription }
  }
  func deviceName(_ session: Session) -> String {
    workspace.nodes.first { $0.id == session.nodeID }?.title ?? "Device"
  }
}
