import Foundation
import Testing
import simd

@testable import PaxWorkspaceKit

struct ParityTests {
  @Test func gfmPreservesNestedStructureAndCode() {
    let source = """
      9. **First**
         - [x] nested
         - [ ] next
      10. Second

      > Quote
      >
      > ```swift
      > let x = "```"
      > ```

      [guide][ref] and ![diagram][pic]

      [ref]: https://example.com/docs
      [pic]: https://example.com/image.png

      | Name | Value |
      | :--- | ---: |
      | a\\|b | **bold** |
      """
    let blocks = MarkdownParser.blocks(source)
    #expect(blocks[0].kind == .item("9.", nil))
    #expect(blocks[0].children[1].kind == .item("•", true))
    #expect(blocks[0].children[2].kind == .item("•", false))
    #expect(blocks[1].kind == .item("10.", nil))
    let quote = blocks.first { $0.kind == .quote }
    #expect(quote?.children.last?.text == "let x = \"```\"")
    #expect(
      blocks.contains {
        $0.spans.contains { $0.link?.absoluteString == "https://example.com/docs" }
      })
    #expect(
      blocks.contains { $0.kind == .image("https://example.com/image.png") && $0.text == "diagram" }
    )
    #expect(blocks.last?.rows[1][0].map(\.text).joined() == "a|b")
    #expect(blocks.last?.alignments == [0, 2])
  }
  @Test func markdownLinksAndUnfinishedInputFailSafely() {
    for bad in [
      "javascript:alert(1)", "file:///etc/passwd", "net.paxtech.workspace://auth/callback",
      "https://user:pass@example.com", "https:///", "data:image/png;base64,a",
    ] {
      #expect(MarkdownParser.safeLink(bad) == nil)
    }
    #expect(MarkdownParser.safeLink("mailto:hello@example.com") != nil)
    let code = MarkdownParser.blocks("````swift\nlet s = \"```\"\n")
    #expect(code.first?.text == "let s = \"```\"")
    #expect(MarkdownParser.blocks(String(repeating: "x", count: 150_001)).count == 1)
    let html = MarkdownParser.blocks("<script>alert('x')</script>")
    #expect(html.first?.spans.map(\.text).joined().contains("<script>") == true)
  }
  @Test func highlightDoesNotTreatStringsAsComments() {
    let code = "let url = \"https://example.com\" // comment\nlet n = 42"
    let tokens = CodeHighlight.tokens(code, language: "swift")
    #expect(tokens.filter { $0.kind == .comment }.count == 1)
    #expect(tokens.filter { $0.kind == .string }.count == 1)
    #expect(tokens.filter { $0.kind == .number }.count == 1)
    #expect(CodeHighlight.tokens(String(repeating: "x", count: 20_001), language: "swift").isEmpty)
    #expect(CodeHighlight.tokens(code, language: "unknown").isEmpty)
  }
  @Test func sendMotionEligibilityAndActivityGrouping() {
    #expect(SendMotionPolicy.allows("Hello 世界 👋", reducedMotion: false, accessibilityText: false))
    for text in [
      "```swift\nlet x = 1", "مرحبا", "Hello \u{202e}world", String(repeating: "x", count: 2001),
    ] {
      #expect(!SendMotionPolicy.allows(text, reducedMotion: false, accessibilityText: false))
    }
    #expect(!SendMotionPolicy.allows("hello", reducedMotion: true, accessibilityText: false))
    #expect(!SendMotionPolicy.allows("hello", reducedMotion: false, accessibilityText: true))
    let messages = [
      ChatItem(id: "1", turn: "a", role: "thought", text: "Thinking"),
      ChatItem(id: "2", turn: "a", role: "tool", text: "tool", status: "failed"),
      ChatItem(id: "3", turn: "a", role: "assistant", text: "Answer"),
      ChatItem(id: "4", turn: "b", role: "tool", text: "next"),
      ChatItem(id: "5", turn: "c", role: "tool", text: "other"),
      ChatItem(id: "6", turn: "c", role: "approval", text: "Approve"),
    ]
    let groups = ConversationEntry.grouped(messages)
    #expect(groups.count == 4)
    #expect(groups[0].messages.count == 2)
    #expect(groups[0].status == "Failed")
    #expect(groups.last!.isActivity)
    #expect(groups.last!.messages.count == 2)
  }
  @Test func manyPermissionRequestsFoldTogetherWhileConversationStaysSeparate() {
    let activity = (1...30).flatMap { index in
      [
        ChatItem(id: "permission:\(index)", turn: "turn", role: "approval", text: "Review request"),
        ChatItem(id: "tool:\(index)", turn: "turn", role: "tool", text: "Output"),
      ]
    }
    let question = ChatItem(id: "question", turn: "turn", role: "user", text: "Question")
    let answer = ChatItem(id: "answer", turn: "turn", role: "agent", text: "Visible answer")
    let messages = [question] + activity + [answer]

    let groups = ConversationEntry.grouped(messages)

    #expect(groups.map { $0.messages.count } == [1, 60, 1])
    #expect(groups.first?.messages == [question])
    #expect(groups.last?.messages == [answer])
    #expect(!groups.first!.isActivity)
    #expect(!groups.last!.isActivity)
    #expect(groups[1].isActivity)
    #expect(groups[1].id == "permission:1")
    #expect(groups.flatMap(\.messages) == messages)
  }
  @Test func permissionOnlyGroupsRespectTurnAndReplyBoundaries() {
    let messages = [
      ChatItem(id: "p1", turn: "a", role: "approval", text: "Review", title: "Read files"),
      ChatItem(id: "p2", turn: "a", role: "approval", text: "Review"),
      ChatItem(id: "p3", turn: "b", role: "approval", text: "Review", title: "Run checks"),
      ChatItem(id: "answer", turn: "b", role: "assistant", text: "Visible answer"),
      ChatItem(id: "p4", turn: "b", role: "approval", text: "Review", title: "Write files"),
    ]

    let groups = ConversationEntry.grouped(messages)

    #expect(groups.map { $0.messages.count } == [2, 1, 1, 1])
    #expect(groups.map(\.isActivity) == [true, true, false, true])
    #expect(groups[0].subtitle == "Permission request")
    #expect(groups[1].subtitle == "Run checks")
    #expect(messages.last?.activityTitle == "Write files")
    #expect(groups.flatMap(\.messages) == messages)
  }
  @Test func orbitalContinuityAndHiddenBackHemisphere() {
    let orbit = SolarSystem.earth
    #expect(
      simd_length(orbit.position(years: 0.3) - orbit.position(years: 0.3 + orbit.period)) < 0.000001
    )
    #expect(abs(simd_length(SolarSystem.moon(seconds: 0)) - 3.1) < 0.000001)
    #expect(simd_length(SolarSystem.moon(seconds: 0) - SolarSystem.moon(seconds: 80)) < 0.000001)
    #expect(SolarSystem.project(SIMD3(1, 1, 1)) == nil)
    #expect(SolarSystem.project(SIMD3(0, 0, -1)) != nil)
  }
}

actor PagedTransport: APITransport {
  let auth = FixtureTransport()
  var calls: [Int] = []
  var authCodes = 0
  func json(_ request: URLRequest) async throws -> JSON {
    if request.url!.path.hasSuffix("sessions") {
      let page =
        URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first {
          $0.name == "page_num"
        }?.value.flatMap(Int.init) ?? 1
      calls.append(page)
      try await Task.sleep(for: .milliseconds(10))
      return .object([
        "sessions": .array(
          (0..<50).map {
            .fields([
              "session_id": "\(page)-\($0)", "agent_id": "agent", "name": "Session \(page)-\($0)",
            ])
          })
      ])
    }
    if request.url!.path.hasSuffix("authenticate"), let body = request.httpBody,
      try JSON.parse(body)["grant_type"].string == "authorization_code"
    {
      authCodes += 1
    }
    return try await auth.json(request)
  }
  nonisolated func events(_ request: URLRequest) -> AsyncThrowingStream<ServerEvent, Error> {
    auth.events(request)
  }
}
@MainActor struct WorkspaceParityTests {
  @Test func pollingPreservesPagesAndDeduplicatesConcurrentLoads() async throws {
    let api = PagedTransport()
    let vault = MemoryVault()
    vault.value.session = UserSession(subject: "user_fixture", refreshToken: "synthetic")
    let model = WorkspaceModel(api: api, vault: vault)
    await model.restore()
    await model.refresh(more: true)
    #expect(model.workspace.sessions.count == 100)
    await model.refresh()
    #expect(model.workspace.sessions.count == 100)
    async let a: Void = model.refresh(more: true)
    async let b: Void = model.refresh(more: true)
    _ = await (a, b)
    #expect(model.workspace.sessions.count == 150)
    #expect(Set(model.workspace.sessions.map(\.id)).count == 150)
    #expect(await api.calls == [1, 2, 1, 2, 3])
  }
  @Test func coldCallbackIsConsumedExactlyOnceAfterRestore() async throws {
    let api = PagedTransport()
    let vault = MemoryVault()
    let pending = OAuthPending(
      state: "native.android.test", verifier: "verifier", expiresAt: Date().addingTimeInterval(300))
    vault.value.oauth = pending
    let model = WorkspaceModel(api: api, vault: vault)
    let callback = URL(
      string: "net.paxtech.workspace://auth/callback?state=native.android.test&code=synthetic")!
    await model.receiveCallback(callback)
    #expect(model.account == nil)
    await model.restore()
    await model.receiveCallback(callback)
    #expect(model.account?.userID == "usr_fixture")
    #expect(vault.value.oauth == nil)
    #expect(await api.authCodes == 1)
  }
}

private actor AccountRaceTransport: APITransport {
  var pending: CheckedContinuation<JSON, Never>?
  func json(_ request: URLRequest) async throws -> JSON {
    if request.url!.path.hasSuffix("bootstrap") {
      return .fields([
        "status": "ready", "api_url": UserAuth.apiOrigin, "user_id": "usr_new",
        "email": "new@example.invalid", "region": "us",
      ])
    }
    let body = try JSON.parse(request.httpBody!)
    if body["grant_type"].string == "refresh_token" {
      return await withCheckedContinuation { pending = $0 }
    }
    return try tokens("user_new")
  }
  func releaseOldRefresh() throws {
    pending?.resume(returning: try tokens("user_old"))
    pending = nil
  }
  private func tokens(_ subject: String) throws -> JSON {
    let claims = try JSON.object([
      "sub": .string(subject),
      "exp": .number(Date().addingTimeInterval(3600).timeIntervalSince1970),
    ]).data()
    return .object([
      "access_token": .string("header." + UserAuth.base64URL(claims) + ".signature"),
      "refresh_token": .string("synthetic-" + subject), "user": .fields(["id": subject]),
    ])
  }
  nonisolated func events(_ request: URLRequest) -> AsyncThrowingStream<ServerEvent, Error> {
    AsyncThrowingStream { $0.finish() }
  }
}
@MainActor struct AccountRaceTests {
  @Test func lateRefreshCannotOverwriteNewLogin() async throws {
    let api = AccountRaceTransport()
    let vault = MemoryVault()
    vault.value.session = UserSession(subject: "user_old", refreshToken: "old-synthetic")
    let auth = UserAuth(api: api, vault: vault)
    let old = Task { try await auth.accessToken() }
    for _ in 0..<100 {
      if await api.pending != nil { break }
      try await Task.sleep(for: .milliseconds(2))
    }
    #expect(await api.pending != nil)
    _ = try auth.start()
    let state = try #require(vault.value.oauth?.state)
    let account = try await auth.finish(
      URL(string: "net.paxtech.workspace://auth/callback?state=\(state)&code=new-synthetic")!)
    try await api.releaseOldRefresh()
    do {
      _ = try await old.value
      Issue.record("Old refresh must fail")
    } catch {}
    #expect(account?.userID == "usr_new")
    #expect(vault.value.session?.subject == "user_new")
    #expect(vault.value.account?.userID == "usr_new")
  }
}
