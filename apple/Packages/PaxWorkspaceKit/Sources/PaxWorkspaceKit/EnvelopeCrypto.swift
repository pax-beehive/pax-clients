import CryptoKit
import Foundation

struct Envelope: Codable, Sendable {
  enum CodingKeys: String, CodingKey {
    case protocolVersion = "protocol_version"
    case cipherVersion = "cipher_version"
    case keyEpoch = "key_epoch"
    case recordID = "record_id"
    case agentID = "agent_id"
    case sessionID = "session_id"
    case kind, nonce, payload
  }
  var protocolVersion = 1
  var cipherVersion = 1
  let keyEpoch: Int
  let recordID: String
  let agentID: String
  let sessionID: String
  let kind: String
  var nonce: String
  var payload: String
  init(
    epoch: Int, id: String, agent: String, session: String, kind: String, nonce: String,
    payload: String = ""
  ) {
    keyEpoch = epoch
    recordID = id
    agentID = agent
    sessionID = session
    self.kind = kind
    self.nonce = nonce
    self.payload = payload
  }
  static func decode(_ value: JSON) throws -> Envelope {
    try JSONDecoder().decode(Envelope.self, from: value.data())
  }
  func json() throws -> JSON { try JSON.parse(JSONEncoder().encode(self)) }
}
enum EnvelopeCrypto {
  static func bytes(_ value: String) throws -> Data {
    guard let data = Data(base64Encoded: value) else {
      throw ClientFailure(message: "Invalid base64 encoding")
    }
    return data
  }
  static func aad(_ e: Envelope) throws -> Data {
    try JSON.array([
      .number(1), .number(1), .string(String(e.keyEpoch)), .string(e.recordID),
      .string(e.agentID), .string(e.sessionID), .string(e.kind),
    ]).data()
  }
  static func key(_ root: Data, direction: String, envelope e: Envelope) throws -> SymmetricKey {
    try require(
      root.count == 32 && ["command", "event"].contains(direction),
      "Invalid encryption key or direction")
    try require(
      e.protocolVersion == 1 && e.cipherVersion == 1 && e.keyEpoch > 0
        && (try bytes(e.nonce)).count == 12, "Unsupported encryption envelope")
    for value in [e.recordID, e.agentID, e.sessionID, e.kind] {
      try require(
        !value.isEmpty && value.count <= 512 && !value.contains("\0"), "Invalid encryption metadata"
      )
    }
    return HKDF<SHA256>.deriveKey(
      inputKeyMaterial: SymmetricKey(data: root), salt: Data(),
      info: Data("pax/e2ee/v1/\(direction)\0\(e.agentID)\0\(e.sessionID)\0\(e.keyEpoch)".utf8),
      outputByteCount: 32)
  }
  static func open(_ root: Data, direction: String, envelope e: Envelope) throws -> Data {
    let key = try key(root, direction: direction, envelope: e)
    return try decrypt(key, nonce: bytes(e.nonce), aad: aad(e), payload: bytes(e.payload))
  }
  static func decrypt(_ key: SymmetricKey, nonce: Data, aad: Data, payload: Data) throws -> Data {
    try require(payload.count >= 16 && nonce.count == 12, "Invalid ciphertext")
    let box = try AES.GCM.SealedBox(
      nonce: AES.GCM.Nonce(data: nonce), ciphertext: payload.dropLast(16), tag: payload.suffix(16))
    return try AES.GCM.open(box, using: key, authenticating: aad)
  }
  static func seal(
    _ root: Data, agent: String, session: String, epoch: Int, id: String, plain: Data
  ) throws -> Envelope {
    let nonce = AES.GCM.Nonce()
    var e = Envelope(
      epoch: epoch, id: id, agent: agent, session: session, kind: "acp_command",
      nonce: nonce.withUnsafeBytes { Data($0).base64EncodedString() })
    let box = try AES.GCM.seal(
      plain, using: key(root, direction: "command", envelope: e), nonce: nonce,
      authenticating: aad(e))
    e.payload = (box.ciphertext + box.tag).base64EncodedString()
    return e
  }
  static func unwrap(_ p: Pairing, package: JSON) throws -> Data {
    try require(
      package["pairing_id"].string == p.id && package["agent_id"].string == p.agentID
        && package["device_id"].string == p.deviceID && package["key_epoch"].int == p.epoch
        && (try bytes(package["recipient_public_key"].string)) == bytes(p.publicKey),
      "Device authorization identity mismatch")
    let privateKey = try P256.KeyAgreement.PrivateKey(derRepresentation: bytes(p.privateKey))
    let sender = try P256.KeyAgreement.PublicKey(
      x963Representation: bytes(package["sender_ephemeral_public_key"].string))
    let shared = try privateKey.sharedSecretFromKeyAgreement(with: sender)
    let aad = try JSON.array([
      .string("pax/e2ee/root-package/v1"), .string(p.id), .string(p.agentID), .string(p.deviceID),
      .number(Double(p.epoch)), .string(p.publicKey), package["sender_ephemeral_public_key"],
    ]).data()
    let key = shared.hkdfDerivedSymmetricKey(
      using: SHA256.self, salt: Data(SHA256.hash(data: try bytes(p.secret))), sharedInfo: aad,
      outputByteCount: 32)
    let root = try decrypt(
      key, nonce: bytes(package["nonce"].string), aad: aad,
      payload: bytes(package["ciphertext"].string))
    try require(root.count == 32, "Invalid root key")
    return root
  }
}
@MainActor final class DevicePairing {
  let repo: WorkspaceRepository
  let vault: any Vault
  init(repo: WorkspaceRepository, vault: any Vault) {
    self.repo = repo
    self.vault = vault
  }
  func key(_ agent: String) throws -> RootKey? {
    let data = try vault.read()
    return data.keys.first { $0.account == data.account?.userID && $0.agentID == agent }
  }
  func start(_ agent: String, epoch: Int = 1) async throws -> Pairing {
    var data = try vault.read()
    guard let account = data.account else { throw ClientFailure(message: "Sign in to continue") }
    if let pending = data.pairings.first(where: {
      $0.account == account.userID && $0.agentID == agent
    }) {
      return pending
    }
    let key = P256.KeyAgreement.PrivateKey()
    let p = Pairing(
      account: account.userID, agentID: agent, epoch: epoch, id: "pair_" + UUID().uuidString,
      deviceID: "device_" + UUID().uuidString,
      privateKey: key.derRepresentation.base64EncodedString(),
      publicKey: key.publicKey.x963Representation.base64EncodedString(),
      secret: try UserAuth.random(16).base64EncodedString())
    let context = try JSON.array([
      .string("pax/e2ee/pairing-public/v1"), .string(p.id), .string(agent), .string(p.deviceID),
      .number(Double(epoch)), .string(p.publicKey),
    ]).data()
    let commitment = SHA256.hash(
      data: Data("pax/e2ee/pairing-commitment/v1\0".utf8) + (try EnvelopeCrypto.bytes(p.secret))
        + Data([0]) + context)
    data.pairings.append(p)
    try vault.save(data)
    try await repo.post(
      ["agents", agent, "e2ee", "pairings"],
      .object([
        "pairing_id": .string(p.id), "device_id": .string(p.deviceID),
        "device_name": .string("PaxWorkspace iOS"), "key_epoch": .number(Double(epoch)),
        "recipient_public_key": .string(p.publicKey),
        "secret_commitment": .string(Data(commitment).base64EncodedString()),
      ]))
    return p
  }
  func finish(_ p: Pairing) async throws {
    let package = try await repo.get(
      ["agents", p.agentID, "e2ee", "key-packages", p.deviceID],
      query: ["key_epoch": String(p.epoch)])
    let root = try EnvelopeCrypto.unwrap(p, package: package)
    var saved = try vault.read()
    try require(saved.account?.userID == p.account, "Account changed")
    saved.keys.removeAll { $0.account == p.account && $0.agentID == p.agentID }
    saved.keys.append(
      RootKey(
        account: p.account, agentID: p.agentID, epoch: p.epoch, encoded: root.base64EncodedString())
    )
    saved.pairings.removeAll { $0.id == p.id }
    try vault.save(saved)
  }
  static func command(_ p: Pairing) -> String {
    let data = try? JSON.fields(["agent_id": p.agentID, "pairing_secret": p.secret]).data()
    let json = String(data: data ?? Data(), encoding: .utf8)!.replacingOccurrences(
      of: "'", with: "'\"'\"'")
    return
      "curl --unix-socket \"$HOME/.paxd/paxd.sock\" -X POST 'http://paxd/v1/e2ee/pairings/\(p.id)/complete' -H 'Content-Type: application/json' --data '\(json)'"
  }
}
