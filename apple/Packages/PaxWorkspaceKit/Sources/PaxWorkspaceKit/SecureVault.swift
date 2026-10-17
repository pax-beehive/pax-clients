import Foundation
import Security

struct Account: Codable, Sendable {
  let userID: String
  let email: String
  let region: String
}
struct UserSession: Codable, Sendable {
  let subject: String
  let refreshToken: String
}
struct OAuthPending: Codable, Sendable {
  let state: String
  let verifier: String
  let expiresAt: Date
}
struct RootKey: Codable, Sendable {
  let account: String
  let agentID: String
  let epoch: Int
  let encoded: String
}
struct Pairing: Codable, Sendable {
  let account: String
  let agentID: String
  let epoch: Int
  let id: String
  let deviceID: String
  let privateKey: String
  let publicKey: String
  let secret: String
}
struct VaultData: Codable, Sendable {
  var pendingUpgrades: [String: PendingUpgrade]?
  var account: Account?
  var session: UserSession?
  var oauth: OAuthPending?
  var keys: [RootKey] = []
  var pairings: [Pairing] = []
}
@MainActor protocol Vault: AnyObject {
  func read() throws -> VaultData
  func save(_ value: VaultData) throws
}
@MainActor final class SecureVault: Vault {
  private let query: [String: Any]
  init(service: String = "net.paxtech.workspace.native") {
    query = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: "vault.v1", kSecAttrSynchronizable as String: false,
    ]
  }
  func read() throws -> VaultData {
    var request = query
    request[kSecReturnData as String] = true
    request[kSecMatchLimit as String] = kSecMatchLimitOne
    var result: CFTypeRef?
    let status = SecItemCopyMatching(request as CFDictionary, &result)
    if status == errSecItemNotFound { return VaultData() }
    try require(
      status == errSecSuccess, "Secure storage is unavailable. Unlock your device and try again.")
    guard let data = result as? Data else { throw ClientFailure(message: "Invalid secure storage") }
    return try JSONDecoder().decode(VaultData.self, from: data)
  }
  func save(_ value: VaultData) throws {
    let data = try JSONEncoder().encode(value)
    let changes: [String: Any] = [
      kSecValueData as String: data,
      kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
    ]
    var status = SecItemUpdate(query as CFDictionary, changes as CFDictionary)
    if status == errSecItemNotFound {
      status = SecItemAdd(query.merging(changes) { _, new in new } as CFDictionary, nil)
    }
    try require(status == errSecSuccess, "Unable to save credentials securely")
  }
}
