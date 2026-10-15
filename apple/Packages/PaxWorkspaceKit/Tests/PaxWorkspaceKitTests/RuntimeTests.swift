import Foundation
import Testing

@testable import PaxWorkspaceKit

@MainActor final class MemoryVault: Vault {
  var value = VaultData()
  func read() throws -> VaultData { value }
  func save(_ value: VaultData) throws { self.value = value }
}
actor FixtureTransport: APITransport {
  var requests: [URLRequest] = []
  var conversationCount = 0
  var refreshCount = 0
  var finishSend = true
  var afterSend = false
  var plainHistoryCount = 0
  func setFinish(_ value: Bool) { finishSend = value }
  func json(_ request: URLRequest) async throws -> JSON {
    requests.append(request)
    let path = request.url!.path
    if path.hasSuffix("authenticate") {
      refreshCount += 1
      try await Task.sleep(for: .milliseconds(20))
      let claims = try JSON.object([
        "sub": .string("user_fixture"),
        "exp": .number(Date().addingTimeInterval(3600).timeIntervalSince1970),
      ]).data()
      return .object([
        "access_token": .string("header." + UserAuth.base64URL(claims) + ".signature"),
        "refresh_token": .string("rotated-synthetic"), "user": .fields(["id": "user_fixture"]),
      ])
    }
    if path.hasSuffix("bootstrap") {
      return .fields([
        "status": "ready", "api_url": UserAuth.apiOrigin, "user_id": "usr_fixture",
        "email": "fixture@example.invalid", "region": "us",
      ])
    }
    if path.hasSuffix("history") {
      plainHistoryCount += 1
      let messages: [JSON] =
        afterSend
        ? [
          .object([
            "message_id": .string("canonical-user"), "turn_id": .string("turn"),
            "role": .string("user"), "session_seq": .number(1),
            "parts": .array([.fields(["text": "hello"])]),
          ]),
          .object([
            "message_id": .string("canonical-agent"), "turn_id": .string("turn"),
            "role": .string("assistant"), "session_seq": .number(2),
            "parts": .array([.fields(["text": "Hello 世界"])]),
          ]),
        ] : []
      return .object([
        "messages": .array(messages), "pagination": .object(["has_older": .bool(false)]),
      ])
    }
    return .object([:])
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
    func emit(_ value: JSON) throws {
      continuation.yield(
        ServerEvent(id: "", data: String(decoding: try value.data(), as: UTF8.self)))
    }
    if request.url!.path.hasSuffix("conversation") {
      conversationCount += 1
      try emit(
        .fields(["type": "turn_started", "session_id": "session_fixture", "turn_id": "turn"]))
      try emit(
        .object(["type": .string("acp"), "turn_id": .string("turn"), "frame": chunk("Hello ")]))
      try await Task.sleep(for: .milliseconds(180))
      try emit(.object(["type": .string("acp"), "turn_id": .string("turn"), "frame": chunk("世界")]))
      if finishSend {
        afterSend = true
        try emit(.fields(["type": "done"]))
      }
    } else {
      try emit(.fields(["type": "no_running_turn"]))
      try await Task.sleep(for: .seconds(30))
    }
  }
}
@MainActor struct RuntimeTests {
  func setup(transport: String = "manager") async throws -> (
    SessionRuntime, FixtureTransport, UserAuth, MemoryVault
  ) {
    let api = FixtureTransport()
    let vault = MemoryVault()
    vault.value.session = UserSession(subject: "user_fixture", refreshToken: "synthetic")
    let auth = UserAuth(api: api, vault: vault)
    _ = try await auth.restore()
    let repo = WorkspaceRepository(auth: auth, api: api)
    let pairing = DevicePairing(repo: repo, vault: vault)
    let session = Session(
      raw: .fields([
        "session_id": "session_fixture", "node_id": "node_fixture", "agent_id": "agent_fixture",
        "transport": transport,
      ]))
    return (SessionRuntime(session: session, repo: repo, pairing: pairing), api, auth, vault)
  }
  func waitUntil(_ predicate: () -> Bool) async throws {
    for _ in 0..<200 {
      if predicate() { return }
      try await Task.sleep(for: .milliseconds(10))
    }
    Issue.record("Timed out waiting for runtime state")
  }
  @Test func immediateEchoLiveChunksAndCanonicalReconciliation() async throws {
    let (runtime, api, _, _) = try await setup()
    defer { runtime.pause() }
    runtime.connect()
    try await waitUntil { runtime.canSend }
    #expect(runtime.send("hello"))
    #expect(runtime.messages.first?.text == "hello")
    #expect(runtime.busy)
    #expect(!runtime.send("duplicate"))
    try await waitUntil { runtime.messages.last?.text == "Hello " }
    #expect(runtime.busy)  // The first chunk renders before the POST completes.
    try await waitUntil { runtime.canSend }
    #expect(runtime.messages.map(\.id) == ["canonical-user", "canonical-agent"])
    #expect(runtime.messages.last?.text == "Hello 世界")
    #expect(await api.conversationCount == 1)
  }
  @Test func interruptedMutationIsNeverAutomaticallyResent() async throws {
    let (runtime, api, _, _) = try await setup()
    await api.setFinish(false)
    defer { runtime.pause() }
    runtime.connect()
    try await waitUntil { runtime.canSend }
    #expect(runtime.send("hello"))
    try await waitUntil { runtime.connection == "Needs reconnect" }
    #expect(runtime.messages.first?.status == "Delivery unconfirmed")
    #expect(!runtime.send("again"))
    #expect(await api.conversationCount == 1)
  }
  @Test func encryptedSessionWithoutKeyNeverRequestsPlainHistory() async throws {
    let (runtime, api, _, _) = try await setup(transport: "e2ee")
    defer { runtime.pause() }
    runtime.connect()
    try await waitUntil { runtime.locked }
    #expect(!runtime.send("secret"))
    #expect(await api.plainHistoryCount == 0)
    #expect(await api.conversationCount == 0)
  }
  @Test func unsupportedTransportFailsClosed() async throws {
    let (runtime, api, _, _) = try await setup(transport: "future")
    defer { runtime.pause() }
    runtime.connect()
    try await waitUntil { runtime.connection == "Unavailable" }
    #expect(!runtime.send("secret"))
    #expect(await api.plainHistoryCount == 0)
  }
  @Test func rotatingRefreshIsSingleFlight() async throws {
    let api = FixtureTransport()
    let vault = MemoryVault()
    vault.value.session = UserSession(subject: "user_fixture", refreshToken: "synthetic")
    let auth = UserAuth(api: api, vault: vault)
    async let first = auth.accessToken()
    async let second = auth.accessToken()
    let tokens = try await (first, second)
    #expect(tokens.0 == tokens.1)
    #expect(await api.refreshCount == 1)
    #expect(vault.value.session?.refreshToken == "rotated-synthetic")
  }
  @Test func pauseFencesPendingStreamUpdates() async throws {
    let (runtime, api, _, _) = try await setup()
    runtime.connect()
    try await waitUntil { runtime.canSend }
    #expect(runtime.send("hello"))
    try await waitUntil { runtime.messages.last?.text == "Hello " }
    runtime.pause()
    let snapshot = runtime.messages
    try await Task.sleep(for: .milliseconds(250))
    #expect(runtime.messages == snapshot)
    #expect(runtime.connection == "Paused")
    #expect(await api.conversationCount == 1)
  }
}
