import Foundation

@MainActor final class WorkspaceRepository {
  let auth: UserAuth
  let api: any APITransport
  init(auth: UserAuth, api: any APITransport) {
    self.auth = auth
    self.api = api
  }
  func request(
    _ path: [String], method: String = "GET", body: JSON? = nil, query: [String: String] = [:],
    headers: [String: String] = [:]
  ) async throws -> URLRequest {
    let token = try await auth.accessToken()
    return try PaxAPI.request(
      path: ["user", "self"] + path, token: token, method: method, body: body, query: query,
      headers: headers)
  }
  func get(_ path: [String], query: [String: String] = [:]) async throws -> JSON {
    try await api.json(request(path, query: query))
  }
  @discardableResult func post(_ path: [String], _ body: JSON, headers: [String: String] = [:])
    async throws -> JSON
  { try await api.json(request(path, method: "POST", body: body, headers: headers)) }
  func load(page: Int = 1) async throws -> Workspace {
    async let sessions = get(["sessions"], query: ["page_num": String(page), "page_size": "50"])
    async let projects = get(["projects"])
    async let nodes = get(["nodes"])
    async let approvals = get(["approvals"])
    let (s, p, n, a) = try await (sessions, projects, nodes, approvals)
    return Workspace(
      sessions: s["sessions"].array.map(Session.init),
      projects: p["projects"].array.map { Resource(raw: $0, kind: "project") },
      nodes: n["nodes"].array.map { Resource(raw: $0, kind: "node") },
      approvals: a["approvals"].array.map { Resource(raw: $0, kind: "approval") },
      hasMore: s["sessions"].array.count == 50)
  }
  func sessionPage(_ page: Int) async throws -> (sessions: [Session], hasMore: Bool) {
    let result = try await get(["sessions"], query: ["page_num": String(page), "page_size": "50"])
    let sessions = result["sessions"].array.map(Session.init)
    return (sessions, sessions.count == 50)
  }
  func createProject(_ name: String) async throws -> Resource {
    let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
    try require(!name.isEmpty && name.utf8.count <= 100, "Use a project name of 1–100 UTF-8 bytes")
    let result = try await post(["projects"], .fields(["display_name": name]))
    try require(
      !result["project"]["project_id"].string.isEmpty,
      "Missing project identity; refresh before trying again")
    return Resource(raw: result["project"], kind: "project")
  }
  func decide(_ approval: Resource, option: JSON) async throws {
    try require(!option["option_id"].string.isEmpty, "Invalid approval option")
    guard let account = try auth.vault.read().account else {
      throw ClientFailure(message: "Sign in to continue")
    }
    try await post(
      ["approvals", approval.id, "decision"],
      .fields([
        "approval_id": approval.id, "decision_option": option["option_id"].string,
        "user_id": account.userID,
      ]))
  }
  func resume(_ approval: Resource) async throws {
    let node = approval.raw["request_node_id"].string
    let agent = approval.raw["request_agent_id"].string
    let session = approval.raw["request_session_id"].string
    if node.isEmpty || agent.isEmpty || session.isEmpty { return }
    let target = try await get(["nodes", node, "agents", agent, "sessions", session])
    if target["transport"].string == "e2ee" { return }
    try require(target.text("transport", "manager") == "manager", "Unsupported transport")
    let request = try await request(
      ["nodes", node, "agents", agent, "conversation"], method: "POST",
      body: .object([
        "session_id": .string(session), "resume": .fields(["approval_id": approval.id]),
      ]))
    var complete = false
    for try await event in api.events(request) {
      let data = try JSON.parse(Data(event.data.utf8))
      if ["done", "turn_done", "interrupted"].contains(data["type"].string) { complete = true }
      if data["type"].string == "error" {
        throw ClientFailure(message: "Approval saved; session could not resume")
      }
    }
    try require(complete, "Approval saved; resume unconfirmed. Reconnect to inspect the session.")
  }
}

struct SessionDraft {
  let id = "session_" + UUID().uuidString.lowercased()
  var projectID = ""
  var nodeID = ""
  var agentID = ""
  var cwd = ""
  var name = ""
  var valid: Bool { !nodeID.isEmpty && !agentID.isEmpty && Self.validPath(cwd) }
  static func validPath(_ value: String) -> Bool {
    let path = value.trimmingCharacters(in: .whitespacesAndNewlines)
    return
      (path.hasPrefix("/") || path.hasPrefix("\\\\")
      || path.range(of: #"^[A-Za-z]:[\\/]"#, options: .regularExpression) != nil)
      && !path.contains("\0") && !path.contains("\n") && !path.contains("\r")
  }
  var route: [String] { ["nodes", nodeID, "agents", agentID, "sessions"] }
  var body: JSON {
    var fields: [String: JSON] = [
      "session_id": .string(id),
      "name": .string(
        name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
          ? "Mobile session" : name.trimmingCharacters(in: .whitespacesAndNewlines)),
      "source": .string("ios"),
      "workspace_roots": .array([.string(cwd.trimmingCharacters(in: .whitespacesAndNewlines))]),
      "pax_config": .fields(["cwd": cwd.trimmingCharacters(in: .whitespacesAndNewlines)]),
    ]
    if !projectID.isEmpty { fields["primary_project_id"] = .string(projectID) }
    return .object(fields)
  }
  func validate(_ value: JSON, recovery: Bool) throws -> Session {
    let session = Session(raw: value)
    try require(
      session.id == id && session.nodeID == nodeID && session.agentID == agentID
        && session.projectID == projectID, "Session identity mismatch")
    if recovery {
      try require(
        value["pax_config"]["cwd"].string == cwd.trimmingCharacters(in: .whitespacesAndNewlines),
        "Session exists but working directory is not confirmed")
    }
    return session
  }
}
