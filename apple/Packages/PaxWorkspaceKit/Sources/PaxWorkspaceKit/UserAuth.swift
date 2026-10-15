import CryptoKit
import Foundation

@MainActor final class UserAuth {
  nonisolated static let clientID = "client_01M4SSKPMAX8DV2RBWMTTT6SR4"
  nonisolated static let apiOrigin = "https://paxworkspace.net/api/v1/native/client/"
  nonisolated static let redirect = "https://paxworkspace.net/auth/callback"
  let api: any APITransport
  let vault: any Vault
  private var access = ""
  private var expiresAt = Date.distantPast
  private var refreshTask: Task<Void, Error>?
  private var generation = 0
  init(api: any APITransport, vault: any Vault) {
    self.api = api
    self.vault = vault
  }
  static func random(_ count: Int = 32) throws -> Data {
    var bytes = [UInt8](repeating: 0, count: count)
    try require(
      SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess,
      "Secure random generator unavailable")
    return Data(bytes)
  }
  nonisolated static func base64URL(_ data: Data) -> String {
    data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(
      of: "/", with: "_"
    ).replacingOccurrences(of: "=", with: "")
  }
  nonisolated static func challenge(_ verifier: String) -> String {
    base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
  }
  func start() throws -> URL {
    // The deployed relay recognizes this legacy prefix; it is a wire contract,
    // not an Android identity. PKCE and state remain unique to this iOS attempt.
    let state = "native.android." + (try Self.base64URL(Self.random()))
    let verifier = try Self.base64URL(Self.random())
    var saved = try vault.read()
    saved.oauth = OAuthPending(
      state: state, verifier: verifier, expiresAt: Date().addingTimeInterval(600))
    try vault.save(saved)
    var url = URLComponents(string: "https://api.workos.com/user_management/authorize")!
    url.queryItems = [
      "client_id": Self.clientID, "provider": "authkit", "response_type": "code",
      "redirect_uri": Self.redirect, "state": state, "code_challenge": Self.challenge(verifier),
      "code_challenge_method": "S256",
    ].map { URLQueryItem(name: $0.key, value: $0.value) }
    return url.url!
  }
  nonisolated static func callbackCode(_ url: URL, pending: OAuthPending, now: Date = Date()) throws
    -> String
  {
    try require(
      url.absoluteString.utf8.count <= 8192 && url.scheme == "net.paxtech.workspace"
        && url.host == "auth" && url.path == "/callback" && url.user == nil && url.password == nil
        && url.port == nil && url.fragment == nil, "Invalid sign-in callback")
    let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
    try require(Set(items.map(\.name)).count == items.count, "Invalid sign-in callback")
    let query = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
    try require(
      pending.expiresAt > now && query["state"] == pending.state,
      "Sign-in expired or belongs to another request")
    try require(query["error"] == nil, "Sign-in was not completed")
    let code = query["code"] ?? ""
    try require(!code.isEmpty && code.utf8.count <= 4096, "Missing sign-in code")
    return code
  }
  func finish(_ url: URL) async throws -> Account? {
    var saved = try vault.read()
    guard let pending = saved.oauth else { throw ClientFailure(message: "Start sign-in again") }
    let code = try Self.callbackCode(url, pending: pending)
    saved.oauth = nil
    try vault.save(saved)
    // Fence a refresh from the previous account before exchanging a new login.
    generation += 1
    refreshTask?.cancel()
    refreshTask = nil
    access = ""
    expiresAt = .distantPast
    let current = generation
    let tokens = try await token(
      .fields([
        "grant_type": "authorization_code", "client_id": Self.clientID, "code": code,
        "code_verifier": pending.verifier,
      ]))
    try require(current == generation, "Sign-in was cancelled")
    try saveTokens(tokens, expectedSubject: nil)
    return try await bootstrap()
  }
  func cancel() throws {
    var saved = try vault.read()
    saved.oauth = nil
    try vault.save(saved)
    generation += 1
    refreshTask?.cancel()
    refreshTask = nil
  }
  func restore() async throws -> Account? {
    guard try vault.read().session != nil else { return nil }
    return try await bootstrap()
  }
  func bootstrap(region: String? = nil) async throws -> Account? {
    try require(region == nil || ["us", "hk"].contains(region!), "Invalid region")
    let current = generation
    let token = try await accessToken()
    try require(current == generation, "Account changed")
    let data = try await api.json(
      PaxAPI.request(
        path: ["bootstrap"], token: token, method: "POST",
        body: .fields(region.map { ["preferred_region": $0] } ?? [:])))
    try require(current == generation, "Account changed")
    if data["status"].string == "selection_required" { return nil }
    try require(
      data["status"].string == "ready" && data["api_url"].string == Self.apiOrigin
        && ["us", "hk"].contains(data["region"].string) && data["user_id"].string.hasPrefix("usr_"),
      "Invalid account response")
    let account = Account(
      userID: data["user_id"].string, email: data["email"].string, region: data["region"].string)
    var saved = try vault.read()
    saved.account = account
    try vault.save(saved)
    return account
  }
  func accessToken() async throws -> String {
    if !access.isEmpty && expiresAt > Date().addingTimeInterval(60) { return access }
    if let refreshTask {
      let current = generation
      try await refreshTask.value
      try require(current == generation && !access.isEmpty, "Account changed")
      return access
    }
    guard let saved = try vault.read().session else {
      throw ClientFailure(message: "Sign in to continue", status: 401)
    }
    let current = generation
    let task = Task {
      do {
        let result = try await token(
          .fields([
            "grant_type": "refresh_token", "client_id": Self.clientID,
            "refresh_token": saved.refreshToken,
          ]))
        try require(current == generation, "Account changed")
        try saveTokens(result, expectedSubject: saved.subject)
      } catch {
        if current == generation, let failure = error as? ClientFailure,
          [400, 401, 403].contains(failure.status)
        {
          access = ""
          expiresAt = .distantPast
          var value = try vault.read()
          value.session = nil
          value.account = nil
          value.oauth = nil
          try vault.save(value)
        }
        throw error
      }
    }
    refreshTask = task
    defer { if generation == current { refreshTask = nil } }
    try await task.value
    return access
  }
  private func token(_ body: JSON) async throws -> JSON {
    try await api.json(
      PaxAPI.request(
        base: "https://api.workos.com/", path: ["user_management", "authenticate"], method: "POST",
        body: body))
  }
  private func saveTokens(_ data: JSON, expectedSubject: String?) throws {
    let token = data["access_token"].string
    let refresh = data["refresh_token"].string
    let subject = data["user"]["id"].string
    try require(
      !token.isEmpty && !refresh.isEmpty && subject.hasPrefix("user_")
        && (expectedSubject == nil || expectedSubject == subject), "Invalid sign-in response")
    let pieces = token.split(separator: ".")
    try require(pieces.count == 3, "Invalid access token")
    var encoded = String(pieces[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(
      of: "_", with: "/")
    encoded += String(repeating: "=", count: (4 - encoded.count % 4) % 4)
    guard let payload = Data(base64Encoded: encoded) else {
      throw ClientFailure(message: "Invalid access token")
    }
    let claims = try JSON.parse(payload)
    let expiry = Date(timeIntervalSince1970: Double(claims["exp"].int))
    try require(claims["sub"].string == subject && expiry > Date(), "Invalid access token")
    // JWT decoding only schedules refresh; the gateway validates signatures.
    var saved = try vault.read()
    if let old = saved.session, old.subject != subject { saved = VaultData() }
    saved.session = UserSession(subject: subject, refreshToken: refresh)
    if expectedSubject == nil { saved.account = nil }
    try vault.save(saved)  // Persist rotating refresh before exposing the access token.
    access = token
    expiresAt = expiry
  }
  func signOut() async throws {
    if try vault.read().session != nil {
      do {
        let token = try await accessToken()
        _ = try await api.json(PaxAPI.request(path: ["logout"], token: token, method: "POST"))
      } catch let e as ClientFailure where [400, 401, 403].contains(e.status) {}
    }
    generation += 1
    refreshTask?.cancel()
    refreshTask = nil
    access = ""
    expiresAt = .distantPast
    try vault.save(VaultData())
  }
}
