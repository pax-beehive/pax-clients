import Foundation
import Testing

@testable import PaxWorkspaceKit

struct ProtocolTests {
  private func vectors() throws -> JSON {
    try JSON.parse(
      Data(
        contentsOf: Bundle.module.url(
          forResource: "web-vectors", withExtension: "json", subdirectory: "Fixtures")!))
  }
  @Test func webEnvelopeAndTamperRejection() throws {
    let fixture = try vectors()
    let root = try EnvelopeCrypto.bytes(fixture["root"].string)
    let envelope = try Envelope.decode(fixture["envelope"])
    #expect(
      try String(
        data: EnvelopeCrypto.open(root, direction: "event", envelope: envelope), encoding: .utf8)
        == fixture["plaintext"].string)
    #expect(throws: (any Error).self) {
      try EnvelopeCrypto.open(root, direction: "command", envelope: envelope)
    }
    var tampered = fixture["envelope"].object
    tampered["session_id"] = .string("other-session")
    #expect(throws: (any Error).self) {
      try EnvelopeCrypto.open(
        root, direction: "event", envelope: Envelope.decode(.object(tampered)))
    }
    var wrongKey = root
    wrongKey[0] ^= 1
    #expect(throws: (any Error).self) {
      try EnvelopeCrypto.open(wrongKey, direction: "event", envelope: envelope)
    }
    var damaged = envelope
    var bytes = try EnvelopeCrypto.bytes(damaged.payload)
    bytes[0] ^= 1
    damaged.payload = bytes.base64EncodedString()
    #expect(throws: (any Error).self) {
      try EnvelopeCrypto.open(root, direction: "event", envelope: damaged)
    }
  }
  @Test func webRootPackageInteroperability() throws {
    let fixture = try vectors()
    let v = fixture["pairing"]
    let p = Pairing(
      account: v["account"].string, agentID: v["agentId"].string, epoch: v["epoch"].int,
      id: v["id"].string, deviceID: v["deviceId"].string, privateKey: v["privateKey"].string,
      publicKey: v["publicKey"].string, secret: v["secret"].string)
    #expect(
      try EnvelopeCrypto.unwrap(p, package: fixture["package"])
        == EnvelopeCrypto.bytes(fixture["root"].string))
    var changed = fixture["package"].object
    changed["device_id"] = .string("other-device")
    #expect(throws: (any Error).self) { try EnvelopeCrypto.unwrap(p, package: .object(changed)) }
  }
  @Test func nativeCommandRoundTripAndRandomNonces() throws {
    let root = Data(0..<32)
    let plain = Data("hello 世界".utf8)
    let a = try EnvelopeCrypto.seal(
      root, agent: "agent", session: "manager-session", epoch: 1, id: "cmd_a", plain: plain)
    let b = try EnvelopeCrypto.seal(
      root, agent: "agent", session: "manager-session", epoch: 1, id: "cmd_a", plain: plain)
    #expect(a.nonce != b.nonce)
    #expect(try EnvelopeCrypto.open(root, direction: "command", envelope: a) == plain)
    #expect(throws: (any Error).self) {
      try EnvelopeCrypto.open(root, direction: "event", envelope: a)
    }
    // Synthetic vector only; the script validates it with the original web code.
    if let path = ProcessInfo.processInfo.environment["PAX_IOS_INTEROP_OUTPUT"] {
      try JSON.object([
        "root": .string(root.base64EncodedString()),
        "plaintext": .string(String(decoding: plain, as: UTF8.self)), "envelope": a.json(),
      ]).data().write(to: URL(fileURLWithPath: path), options: .atomic)
    }
  }
  @Test func sseDispatchAndPartialEvent() throws {
    var p = SSEParser()
    #expect(try p.line(": keepalive") == nil)
    #expect(try p.line("id: 7") == nil)
    #expect(try p.line("data: first") == nil)
    #expect(try p.line("data: second") == nil)
    let event = try p.line("")
    #expect(event?.id == "7")
    #expect(event?.data == "first\nsecond")
    #expect(try p.line("id: bad\0id") == nil)
    #expect(try p.line("data: next") == nil)
    #expect(try p.line("")?.id == "7")
    #expect(try p.line("data: unfinished") == nil)
    #expect(throws: (any Error).self) { try p.line(String(repeating: "x", count: 1_048_577)) }
  }
  @Test func pkceAndStrictCallback() throws {
    #expect(
      UserAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    let pending = OAuthPending(
      state: "state", verifier: "verifier", expiresAt: Date().addingTimeInterval(60))
    #expect(
      try UserAuth.callbackCode(
        URL(string: "net.paxtech.workspace://auth/callback?state=state&code=ok")!, pending: pending)
        == "ok")
    for url in [
      "net.paxtech.workspace://auth/callback?state=state&state=state&code=ok",
      "net.paxtech.workspace://auth/callback?state=other&code=ok",
      "net.paxtech.workspace://evil/callback?state=state&code=ok",
      "net.paxtech.workspace://auth/callback?state=state&code=ok#fragment",
      "net.paxtech.workspace://auth/callback?state=state&error=access_denied",
    ] {
      #expect(throws: (any Error).self) {
        try UserAuth.callbackCode(URL(string: url)!, pending: pending)
      }
    }
    #expect(throws: (any Error).self) {
      try UserAuth.callbackCode(
        URL(string: "net.paxtech.workspace://auth/callback?state=state&code=ok")!, pending: pending,
        now: Date().addingTimeInterval(120))
    }
  }
  @Test func nativeRoutesAndGatewayValidation() throws {
    let request = try PaxAPI.request(
      path: ["user", "self", "sessions"], token: "synthetic", query: ["page_num": "2"])
    #expect(
      request.url?.absoluteString
        == "https://paxworkspace.net/api/v1/native/client/user/self/sessions?page_num=2")
    #expect(throws: (any Error).self) { try PaxAPI.request(path: ["..", "token"]) }
    #expect(throws: (any Error).self) {
      try PaxAPI.request(base: "http://example.com", path: ["sessions"])
    }
    let html = HTTPURLResponse(
      url: request.url!, statusCode: 200, httpVersion: nil,
      headerFields: ["Content-Type": "text/html"])!
    #expect(throws: (any Error).self) { try PaxAPI.validate(html, type: "application/json") }
    let redirect = HTTPURLResponse(
      url: request.url!, statusCode: 302, httpVersion: nil, headerFields: [:])!
    #expect(throws: (any Error).self) { try PaxAPI.validate(redirect, type: "application/json") }
  }
  @Test func timelineChunksCanonicalHistoryAndMixedThoughts() throws {
    var t = Timeline()
    t.prompt("request", turn: "turn", text: "hello")
    for _ in 0..<5000 { t.frame(chunk("x"), turn: "turn") }
    #expect(t.items.count == 2)
    #expect(t.items.last?.text.count == 5000)
    let history = try JSON.parse(
      Data(
        #"{"message_id":"stored","turn_id":"turn","role":"assistant","parts":[{"part_index":0,"part_type":"thought","text":"reason"},{"part_index":1,"part_type":"text","text":"answer"}]}"#
          .utf8))
    t.replaceTurn("turn", messages: [history])
    #expect(t.items.map(\.role) == ["thought", "assistant"])
    #expect(t.items.last?.text == "answer")
    t.remove("stored")
    #expect(t.items.isEmpty)
  }
  @Test func tolerantResourceAndSessionDraft() throws {
    let s = Session(
      raw: try JSON.parse(
        Data(
          #"{"session_id":"s","node_id":"n","agent_id":"a","transport":"future","name":null,"unknown":123}"#
            .utf8)))
    #expect(s.title == "Agent session")
    #expect(s.transport == "future")
    #expect(SessionDraft.validPath("/Users/test/project"))
    #expect(SessionDraft.validPath("C:\\work"))
    #expect(!SessionDraft.validPath("relative"))
    #expect(!SessionDraft.validPath("/safe\nunsafe"))
    var draft = SessionDraft()
    draft.nodeID = "n"
    draft.agentID = "a"
    draft.cwd = "/tmp/work"
    var response = draft.body.object
    response["node_id"] = .string("n")
    response["agent_id"] = .string("a")
    #expect(try draft.validate(.object(response), recovery: true).id == draft.id)
    response["pax_config"] = .object([:])
    #expect(throws: (any Error).self) { try draft.validate(.object(response), recovery: true) }
  }
  @Test func streamingMarkdownPreservesUnclosedFenceAndTables() {
    let blocks = MarkdownParser.blocks(
      "# Title\n\n- [x] Done\n\n| A | B |\n| --- | --- |\n| one | two |\n\n```swift\nlet x = 1")
    #expect(blocks.map(\.kind) == [.heading(1), .item("•", true), .table, .code("swift")])
    #expect(blocks.last?.text == "let x = 1")
  }
}
func chunk(_ text: String) -> JSON {
  .object([
    "method": .string("session/update"),
    "params": .object([
      "update": .object([
        "sessionUpdate": .string("agent_message_chunk"),
        "content": .fields(["type": "text", "text": text]),
      ])
    ]),
  ])
}
