import Foundation
import Observation

@MainActor @Observable final class SessionRuntime {
  let session: Session
  private(set) var messages: [ChatItem] = []
  private(set) var connection = "Connecting"
  private(set) var busy = false
  private(set) var locked = false
  private(set) var hasOlder = false
  private(set) var loadingOlder = false
  var error: String?
  @ObservationIgnored private let repo: WorkspaceRepository
  @ObservationIgnored private let pairing: DevicePairing
  @ObservationIgnored private var timeline = Timeline()
  @ObservationIgnored private var observer: Task<Void, Never>?
  @ObservationIgnored private var sender: Task<Void, Never>?
  @ObservationIgnored private var publisher: Task<Void, Never>?
  @ObservationIgnored private var generation = 0
  @ObservationIgnored private var dirty = false
  @ObservationIgnored private var cursor = 0
  @ObservationIgnored private var before = 0
  @ObservationIgnored private var root: RootKey?
  @ObservationIgnored private var nativeID: String
  @ObservationIgnored private var promptID: String?
  @ObservationIgnored private var pendingRPC: String?
  @ObservationIgnored private var rpcResult: JSON?
  var canSend: Bool { connection == "Connected" && !busy && !locked && sender == nil }
  init(session: Session, repo: WorkspaceRepository, pairing: DevicePairing) {
    self.session = session
    self.repo = repo
    self.pairing = pairing
    nativeID = session.nativeID
  }
  private func route(_ suffix: String) -> [String] {
    ["agents", session.agentID, "sessions", session.id, suffix]
  }
  func connect() {
    pause()
    let epoch = generation
    error = nil
    connection = "Connecting"
    publisher = Task { [weak self] in
      while !Task.isCancelled {
        try? await Task.sleep(for: .milliseconds(50))
        guard let self, !Task.isCancelled else { return }
        self.publish()
      }
    }
    observer = Task { [weak self] in
      guard let self else { return }
      do {
        try require(
          ["manager", "e2ee"].contains(session.transport), "Unsupported session transport")
        root = session.encrypted ? try pairing.key(session.agentID) : nil
        locked = session.encrypted && root == nil
        if locked {
          connection = "Key authorization required"
          return
        }
        try await history(older: false, generation: epoch)
        try check(epoch)
        busy = ["running", "waiting_approval"].contains(session.status)
        connection = "Connected"
        await observe(epoch)
      } catch {
        if epoch == generation && !Task.isCancelled {
          self.error = error.localizedDescription
          connection = "Unavailable"
        }
      }
    }
  }
  func pause() {
    generation += 1
    observer?.cancel()
    observer = nil
    sender?.cancel()
    sender = nil
    publisher?.cancel()
    publisher = nil
    publish()
    connection = "Paused"
    busy = false
    pendingRPC = nil
    rpcResult = nil
    promptID = nil
  }
  private func check(_ epoch: Int) throws {
    try Task.checkCancellation()
    if epoch != generation { throw CancellationError() }
  }
  private func publish() {
    if dirty {
      messages = timeline.items
      dirty = false
    }
  }
  private func history(older: Bool, generation epoch: Int) async throws {
    if session.encrypted {
      try await encryptedHistory(older: older, generation: epoch)
      return
    }
    var query = ["limit": "100", "view": "full"]
    if older && before > 0 { query["before_seq"] = String(before) }
    let page = try await repo.get(["sessions", session.id, "history"], query: query)
    try check(epoch)
    let values = page["messages"].array.sorted { $0["session_seq"].int < $1["session_seq"].int }
    var next = Timeline()
    for value in values { next.history(value) }
    if older { timeline.prepend(next) } else { timeline = next }
    before =
      page["pagination"]["next_before_seq"] == .null
      ? values.first?["session_seq"].int ?? 0 : page["pagination"]["next_before_seq"].int
    hasOlder =
      (page["pagination"]["has_older"].bool || page["pagination"]["has_more"].bool) && before > 0
    dirty = true
    publish()
  }
  private func batch(_ e: Envelope) throws -> JSON {
    guard let root else { throw ClientFailure(message: "Authorize this device first") }
    try require(
      e.agentID == session.agentID && e.sessionID == session.id && e.keyEpoch == root.epoch
        && e.kind == "acp_event", "Encrypted event identity mismatch")
    let result = try JSON.parse(
      EnvelopeCrypto.open(EnvelopeCrypto.bytes(root.encoded), direction: "event", envelope: e))
    guard case .array = result["frames"] else {
      throw ClientFailure(message: "Missing encrypted frames")
    }
    return result
  }
  private func encryptedHistory(older: Bool, generation epoch: Int) async throws {
    var collected = Timeline()
    var after = 0
    var head: Int?
    var turn = ""
    var start = 0
    var hasEarlier = false
    while true {
      var query = ["view": "replay", "limit": "100"]
      if let head {
        query["through_cursor"] = String(head)
        query["turn_ref"] = turn
        query["after_cursor"] = String(after)
      } else if older && before > 0 {
        query["before_turn"] = String(before)
      }
      let page = try await repo.get(route("encrypted-events"), query: query)
      try check(epoch)
      try require(
        page["head_cursor"] != .null && page["turn_start_cursor"] != .null
          && page["head_cursor"].int >= 0 && page["turn_start_cursor"].int >= 0,
        "Invalid replay snapshot")
      let nextHead = page["head_cursor"].int
      let nextStart = page["turn_start_cursor"].int
      let nextTurn = page["turn_ref"].string
      if let head {
        try require(
          head == nextHead && start == nextStart && turn == nextTurn, "Replay snapshot changed")
      } else {
        head = nextHead
        start = nextStart
        turn = nextTurn
        hasEarlier = page["has_older"].bool
      }
      var latest = after
      for stored in page["events"].array {
        let next = stored["cursor"].int
        try require(next > latest && next <= nextHead, "Replay cursor did not advance")
        let value = try batch(Envelope.decode(stored["envelope"]))
        try require(
          turn.isEmpty || value["turn_id"].string == turn, "Encrypted turn identity mismatch")
        for frame in value["frames"].array { collected.frame(frame, turn: value["turn_id"].string) }
        latest = next
      }
      try require(
        page["next_after_cursor"] != .null && page["next_after_cursor"].int == latest
          && (!page["has_more"].bool || latest > after), "Invalid replay pagination")
      after = latest
      if !page["has_more"].bool { break }
    }
    if older {
      timeline.prepend(collected)
    } else {
      timeline = collected
      cursor = head ?? 0
    }
    before = start
    hasOlder = hasEarlier && start > 0
    dirty = true
    publish()
  }
  private func observe(_ epoch: Int) async {
    var delay = 0.5
    while !Task.isCancelled && epoch == generation {
      do {
        if session.encrypted {
          try await encryptedEvents(epoch)
        } else {
          try await plainEvents(epoch)
        }
        delay = 0.5
        try await Task.sleep(for: .seconds(session.encrypted ? 0.5 : 3))
      } catch {
        if Task.isCancelled || epoch != generation { return }
        let retryable =
          (error as? URLError) != nil
          || (error as? ClientFailure).map { [408, 429, 500, 502, 503, 504].contains($0.status) }
            == true
        guard retryable else {
          self.error = error.localizedDescription
          connection = "Needs reconnect"
          return
        }
        connection = "Reconnecting"
        self.error = "Connection interrupted; recovering session."
        do {
          try await Task.sleep(for: .seconds(delay))
          delay = min(delay * 2, 10)
          if !session.encrypted { try await history(older: false, generation: epoch) }
        } catch { if Task.isCancelled { return } }
      }
    }
  }
  private func plainEvents(_ epoch: Int) async throws {
    var turn = ""
    var canonical: [String: JSON] = [:]
    let request = try await repo.request(route("events"))
    for try await event in repo.api.events(request) {
      try check(epoch)
      let data = try JSON.parse(Data(event.data.utf8))
      try require(data.text("session_id", session.id) == session.id, "Event session mismatch")
      switch data["type"].string {
      case "turn_start":
        turn = data["turn_id"].string
        canonical = [:]
        busy = true
      case "history_item":
        let item = data["item"]
        try require(item.text("session_id", session.id) == session.id, "History session mismatch")
        canonical[item["message_id"].string] = item
      case "history_remove":
        let id = data["message_id"].string
        canonical[id] = nil
        timeline.remove(id)
      case "head":
        if !turn.isEmpty {
          timeline.replaceTurn(
            turn,
            messages: canonical.values.sorted { $0["session_seq"].int < $1["session_seq"].int })
        }
      case "acp": timeline.frame(data["frame"], turn: data.text("turn_id", turn))
      case "turn_done", "no_running_turn": busy = false
      case "error": throw ClientFailure(message: data.text("message", "Session failed"))
      case "buffer_miss", "resync":
        throw ClientFailure(message: "Session replay required", status: 503)
      default: break
      }
      dirty = true
      connection = "Connected"
      error = nil
    }
  }
  private func encryptedEvents(_ epoch: Int) async throws {
    let request = try await repo.request(
      route("encrypted-events"), headers: cursor > 0 ? ["Last-Event-ID": String(cursor)] : [:])
    for try await event in repo.api.events(request) {
      try check(epoch)
      guard let next = Int(event.id) else {
        throw ClientFailure(message: "Invalid encrypted cursor")
      }
      if next <= cursor { continue }
      let value = try batch(Envelope.decode(JSON.parse(Data(event.data.utf8))))
      for frame in value["frames"].array {
        timeline.frame(frame, turn: value["turn_id"].string)
        if !frame["result"]["stopReason"].string.isEmpty
          || !frame["result"]["stop_reason"].string.isEmpty
        {
          busy = false
        }
        if frame["result"] != .null || frame["error"] != .null {
          if frame["id"].string == pendingRPC { rpcResult = frame }
          if frame["id"].string == promptID {
            busy = false
            promptID = nil
            if frame["error"] != .null {
              error = frame["error"].text("message", "Agent request failed")
            }
          }
        }
      }
      cursor = next  // Advance only after authenticated, validated application.
      dirty = true
      connection = "Connected"
    }
  }
  func loadOlder() async {
    guard hasOlder && !loadingOlder && !busy else { return }
    loadingOlder = true
    let epoch = generation
    defer { loadingOlder = false }
    do { try await history(older: true, generation: epoch) } catch {
      if epoch == generation && !Task.isCancelled { self.error = error.localizedDescription }
    }
  }
  @discardableResult func send(_ text: String) -> Bool {
    guard canSend && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
      return false
    }
    let id = "prompt_" + UUID().uuidString
    if !session.encrypted {
      observer?.cancel()
      observer = nil
      generation += 1
    }
    let epoch = generation
    let turn = "local:" + id
    busy = true
    error = nil
    timeline.prompt(id, turn: turn, text: text, status: "Sending…")
    dirty = true
    publish()
    sender = Task { [weak self] in
      guard let self else { return }
      do {
        if session.encrypted {
          try await sendEncrypted(id: id, text: text, generation: epoch)
        } else {
          try await sendPlain(id: id, text: text, turn: turn, generation: epoch)
        }
        try check(epoch)
        timeline.status(id, "")
        dirty = true
        publish()
      } catch {
        guard epoch == generation && !Task.isCancelled else { return }
        timeline.status(id, "Delivery unconfirmed")
        dirty = true
        publish()
        self.error = error.localizedDescription
        busy = false
        connection = "Needs reconnect"
        observer?.cancel()
        observer = nil
      }
      guard epoch == generation else { return }
      sender = nil
      if !session.encrypted {
        busy = false
        if connection != "Needs reconnect" {
          observer = Task { [weak self] in await self?.observe(epoch) }
        }
      }
    }
    return true
  }
  private func sendPlain(id: String, text: String, turn: String, generation epoch: Int) async throws
  {
    let request = try await repo.request(
      ["nodes", session.nodeID, "agents", session.agentID, "conversation"], method: "POST",
      body: .fields(["session_id": session.id, "input": text]))
    var terminal = false
    var liveTurn = turn
    for try await event in repo.api.events(request) {
      try check(epoch)
      let data = try JSON.parse(Data(event.data.utf8))
      try require(
        data.text("session_id", session.id) == session.id, "Conversation session mismatch")
      switch data["type"].string {
      case "turn_started":
        liveTurn = data["turn_id"].string
        try require(!liveTurn.isEmpty, "Missing turn identity")
        timeline.prompt(id, turn: liveTurn, text: text)
      case "acp", "approval_required":
        try require(data.text("turn_id", liveTurn) == liveTurn, "Conversation turn mismatch")
        let frame = data["frame"]
        let update =
          frame["params"]["update"] == .null ? frame["update"] : frame["params"]["update"]
        if frame["method"].string != "session/prompt"
          && update["sessionUpdate"].string != "user_message_chunk"
        {
          timeline.frame(frame, turn: liveTurn)
        }
      case "done", "turn_done", "interrupted": terminal = true
      case "error": throw ClientFailure(message: data.text("message", "Agent request failed"))
      default: break
      }
      dirty = true
    }
    try require(terminal, "Delivery outcome is unknown. Reconnect before sending again.")
    try await history(older: false, generation: epoch)
  }
  private func sendEncrypted(id: String, text: String, generation epoch: Int) async throws {
    if nativeID.isEmpty {
      guard let cwd = session.roots.first else {
        throw ClientFailure(message: "Session working directory unavailable")
      }
      let rpcID = "new_" + UUID().uuidString
      pendingRPC = rpcID
      rpcResult = nil
      defer {
        pendingRPC = nil
        rpcResult = nil
      }
      try await command(
        .object([
          "jsonrpc": .string("2.0"), "id": .string(rpcID), "method": .string("session/new"),
          "params": .object(["cwd": .string(cwd), "mcpServers": .array([])]),
        ]))
      let deadline = Date().addingTimeInterval(30)
      while rpcResult == nil && Date() < deadline {
        try check(epoch)
        try await Task.sleep(for: .milliseconds(50))
      }
      guard let result = rpcResult else {
        throw ClientFailure(message: "Session initialization unconfirmed. Reconnect to inspect it.")
      }
      try require(
        result["error"] == .null, result["error"].text("message", "Session initialization failed"))
      nativeID = result["result"].text("sessionId", result["result"]["session_id"].string)
      try require(!nativeID.isEmpty, "Missing native session identity")
    }
    promptID = id
    try await command(
      .object([
        "jsonrpc": .string("2.0"), "id": .string(id), "method": .string("session/prompt"),
        "params": .object([
          "sessionId": .string(nativeID),
          "prompt": .array([.fields(["type": "text", "text": text])]),
        ]),
      ]))
  }
  private func command(_ frame: JSON) async throws {
    guard let root else { throw ClientFailure(message: "Authorize this device first") }
    let id = "cmd_" + UUID().uuidString
    let e = try EnvelopeCrypto.seal(
      EnvelopeCrypto.bytes(root.encoded), agent: session.agentID, session: session.id,
      epoch: root.epoch, id: id, plain: frame.data())
    let receipt = try await repo.post(route("encrypted-commands"), e.json())
    try require(receipt["command_id"].string == id, "Command receipt mismatch")
  }
  func stop() async {
    do {
      if session.encrypted {
        try require(!nativeID.isEmpty, "Native session identity unavailable")
        try await command(
          .object([
            "jsonrpc": .string("2.0"), "method": .string("session/cancel"),
            "params": .fields(["sessionId": nativeID]),
          ]))
      } else {
        try await repo.post(
          route("turn") + ["stop"], .fields(["reason": "user_requested"]),
          headers: ["Idempotency-Key": UUID().uuidString])
      }
      error = "Stop requested; waiting for agent confirmation."
    } catch { self.error = error.localizedDescription }
  }
}
