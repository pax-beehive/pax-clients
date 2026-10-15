import CryptoKit
import Foundation
import Testing

@testable import PaxWorkspaceKit

actor EncryptedFixture: APITransport {
  let fallback = FixtureTransport()
  var pages: [JSON]
  var eventsToSend: [ServerEvent]
  var requests: [URLRequest] = []
  var commands: [Envelope] = []
  init(pages: [JSON], events: [ServerEvent] = []) {
    self.pages = pages
    eventsToSend = events
  }
  func json(_ request: URLRequest) async throws -> JSON {
    requests.append(request)
    if request.url!.path.hasSuffix("encrypted-events") {
      guard !pages.isEmpty else { throw ClientFailure(message: "Unexpected replay request") }
      return pages.removeFirst()
    }
    if request.url!.path.hasSuffix("encrypted-commands") {
      let envelope = try Envelope.decode(JSON.parse(request.httpBody!))
      commands.append(envelope)
      return .fields(["command_id": envelope.recordID])
    }
    return try await fallback.json(request)
  }
  nonisolated func events(_ request: URLRequest) -> AsyncThrowingStream<ServerEvent, Error> {
    AsyncThrowingStream { continuation in
      let task = Task {
        do {
          try await self.stream(request, continuation)
          continuation.finish()
        } catch { continuation.finish(throwing: error) }
      }
      continuation.onTermination = { _ in task.cancel() }
    }
  }
  private func stream(
    _ request: URLRequest, _ continuation: AsyncThrowingStream<ServerEvent, Error>.Continuation
  ) async throws {
    requests.append(request)
    for event in eventsToSend { continuation.yield(event) }
    try await Task.sleep(for: .seconds(30))
  }
}
@MainActor struct EncryptedRuntimeTests {
  let root = Data(0..<32)
  func event(_ text: String, id: String, agent: String = "agent_fixture") throws -> Envelope {
    let nonce = AES.GCM.Nonce()
    var e = Envelope(
      epoch: 1, id: id, agent: agent, session: "session_fixture", kind: "acp_event",
      nonce: nonce.withUnsafeBytes { Data($0).base64EncodedString() })
    let value = JSON.object(["turn_id": .string("turn_fixture"), "frames": .array([chunk(text)])])
    let sealed = try AES.GCM.seal(
      value.data(), using: EnvelopeCrypto.key(root, direction: "event", envelope: e), nonce: nonce,
      authenticating: EnvelopeCrypto.aad(e))
    e.payload = (sealed.ciphertext + sealed.tag).base64EncodedString()
    return e
  }
  func page(_ event: Envelope, cursor: Int, head: Int, more: Bool) throws -> JSON {
    .object([
      "head_cursor": .number(Double(head)), "turn_ref": .string("turn_fixture"),
      "turn_start_cursor": .number(1), "has_more": .bool(more), "has_older": .bool(false),
      "next_after_cursor": .number(Double(cursor)),
      "events": .array([.object(["cursor": .number(Double(cursor)), "envelope": try event.json()])]
      ),
    ])
  }
  func runtime(_ api: EncryptedFixture) async throws -> SessionRuntime {
    let vault = MemoryVault()
    vault.value.session = UserSession(subject: "user_fixture", refreshToken: "synthetic")
    let auth = UserAuth(api: api, vault: vault)
    _ = try await auth.restore()
    vault.value.keys = [
      RootKey(
        account: "usr_fixture", agentID: "agent_fixture", epoch: 1,
        encoded: root.base64EncodedString())
    ]
    let repo = WorkspaceRepository(auth: auth, api: api)
    return SessionRuntime(
      session: Session(
        raw: .fields([
          "session_id": "session_fixture", "node_id": "node_fixture", "agent_id": "agent_fixture",
          "native_id": "native_distinct", "transport": "e2ee",
        ])), repo: repo, pairing: DevicePairing(repo: repo, vault: vault))
  }
  func waitUntil(_ condition: () -> Bool) async throws {
    for _ in 0..<150 {
      if condition() { return }
      try await Task.sleep(for: .milliseconds(10))
    }
    Issue.record("Encrypted runtime did not reach expected state")
  }
  @Test func replaySnapshotPaginationAndAuthenticatedCursor() async throws {
    let a = try event("Hello ", id: "a")
    let b = try event("world", id: "b")
    let c = try event("!", id: "c")
    let api = EncryptedFixture(
      pages: [
        try page(a, cursor: 1, head: 2, more: true), try page(b, cursor: 2, head: 2, more: false),
      ],
      events: [
        ServerEvent(id: "2", data: "deliberately ignored duplicate"),
        ServerEvent(id: "3", data: String(decoding: try c.json().data(), as: UTF8.self)),
      ])
    let r = try await runtime(api)
    defer { r.pause() }
    r.connect()
    try await waitUntil { r.messages.last?.text == "Hello world!" }
    let requests = await api.requests
    let replay = requests.filter { $0.url!.query?.contains("view=replay") == true }
    #expect(replay.count == 2)
    #expect(replay.last?.url?.query?.contains("through_cursor=2") == true)
    #expect(replay.last?.url?.query?.contains("after_cursor=1") == true)
    #expect(requests.last?.value(forHTTPHeaderField: "Last-Event-ID") == "2")
    #expect(!requests.contains { $0.url!.path.hasSuffix("/history") })
  }
  @Test func changedSnapshotAndWrongRouteFailClosed() async throws {
    let a = try event("trusted", id: "a")
    let api = EncryptedFixture(pages: [
      try page(a, cursor: 1, head: 2, more: true), try page(a, cursor: 2, head: 3, more: false),
    ])
    let r = try await runtime(api)
    defer { r.pause() }
    r.connect()
    try await waitUntil { r.connection == "Unavailable" }
    #expect(r.messages.isEmpty)
    #expect(!r.canSend)
    let wrong = try event("must never display", id: "b", agent: "other_agent")
    let bad = EncryptedFixture(
      pages: [try page(a, cursor: 1, head: 1, more: false)],
      events: [ServerEvent(id: "2", data: String(decoding: try wrong.json().data(), as: UTF8.self))]
    )
    let locked = try await runtime(bad)
    defer { locked.pause() }
    locked.connect()
    try await waitUntil { locked.connection == "Needs reconnect" }
    #expect(locked.messages.last?.text == "trusted")
    #expect(!locked.canSend)
  }
  @Test func encryptedPromptUsesNativeIdentityAndEncryptedEndpoint() async throws {
    let a = try event("ready", id: "a")
    let api = EncryptedFixture(pages: [try page(a, cursor: 1, head: 1, more: false)])
    let r = try await runtime(api)
    defer { r.pause() }
    r.connect()
    try await waitUntil { r.canSend }
    #expect(r.send("secret prompt"))
    for _ in 0..<100 {
      if await !api.commands.isEmpty { break }
      try await Task.sleep(for: .milliseconds(10))
    }
    let commands = await api.commands
    #expect(commands.count == 1)
    let frame = try JSON.parse(
      EnvelopeCrypto.open(root, direction: "command", envelope: commands[0]))
    #expect(frame["params"]["sessionId"].string == "native_distinct")
    #expect(commands[0].sessionID == "session_fixture")
    #expect(frame["params"]["prompt"].array.first?["text"].string == "secret prompt")
    #expect(r.messages.last?.id == "user:" + frame["id"].string)
    #expect(await !api.requests.contains { $0.url!.path.hasSuffix("conversation") })
  }
}
