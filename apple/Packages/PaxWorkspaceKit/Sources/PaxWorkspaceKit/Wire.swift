import Foundation

indirect enum JSON: Codable, Sendable, Equatable {
  case object([String: JSON])
  case array([JSON])
  case string(String)
  case number(Double)
  case bool(Bool)
  case null
  init(from decoder: Decoder) throws {
    let c = try decoder.singleValueContainer()
    if c.decodeNil() {
      self = .null
    } else if let v = try? c.decode(Bool.self) {
      self = .bool(v)
    } else if let v = try? c.decode(String.self) {
      self = .string(v)
    } else if let v = try? c.decode(Double.self) {
      self = .number(v)
    } else if let v = try? c.decode([String: JSON].self) {
      self = .object(v)
    } else {
      self = .array(try c.decode([JSON].self))
    }
  }
  func encode(to encoder: Encoder) throws {
    var c = encoder.singleValueContainer()
    switch self {
    case .object(let v): try c.encode(v)
    case .array(let v): try c.encode(v)
    case .string(let v): try c.encode(v)
    case .number(let v): try c.encode(v)
    case .bool(let v): try c.encode(v)
    case .null: try c.encodeNil()
    }
  }
  subscript(_ key: String) -> JSON {
    if case .object(let o) = self { return o[key] ?? .null }
    return .null
  }
  var string: String {
    if case .string(let s) = self { return s }
    if case .number(let n) = self { return String(format: "%.0f", n) }
    return ""
  }
  var int: Int {
    if case .number(let n) = self, n.isFinite, n >= Double(Int.min), n < Double(Int.max) {
      return Int(n)
    }
    return Int(string) ?? 0
  }
  var bool: Bool { self == .bool(true) }
  var array: [JSON] {
    if case .array(let a) = self { return a }
    return []
  }
  var object: [String: JSON] {
    if case .object(let o) = self { return o }
    return [:]
  }
  func text(_ key: String, _ fallback: String = "") -> String {
    self[key] == .null ? fallback : self[key].string
  }
  static func parse(_ data: Data) throws -> JSON { try JSONDecoder().decode(JSON.self, from: data) }
  func data() throws -> Data {
    let e = JSONEncoder()
    e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    return try e.encode(self)
  }
  static func fields(_ fields: [String: String]) -> JSON { .object(fields.mapValues(JSON.string)) }
}

struct Session: Identifiable, Hashable, Sendable {
  let raw: JSON
  var id: String { raw["session_id"].string }
  var nodeID: String { raw["node_id"].string }
  var agentID: String { raw["agent_id"].string }
  var nativeID: String { raw["native_id"].string }
  var projectID: String { raw["primary_project_id"].string }
  var transport: String { raw.text("transport", "manager") }
  var encrypted: Bool { transport == "e2ee" }
  var title: String {
    [raw["name"].string, raw["reported_name"].string, "Agent session"].first { !$0.isEmpty }!
  }
  var status: String { raw.text("runtime_status", "unknown") }
  var agentType: String { raw["agent_type"].string }
  var roots: [String] { raw["workspace_roots"].array.map(\.string) }
  static func == (lhs: Self, rhs: Self) -> Bool { lhs.raw == rhs.raw }
  func hash(into hasher: inout Hasher) { hasher.combine(id) }
}
struct Resource: Identifiable, Sendable, Equatable {
  let raw: JSON
  let kind: String
  var id: String { raw["\(kind)_id"].string }
  var title: String {
    [
      raw["display_name"].string, raw["name"].string, raw["hostname"].string, raw["title"].string,
      raw["agent_type"].string, id,
    ].first { !$0.isEmpty } ?? id
  }
}
struct Workspace: Sendable {
  var sessions: [Session] = []
  var projects: [Resource] = []
  var nodes: [Resource] = []
  var approvals: [Resource] = []
  var hasMore = false
}
struct ClientFailure: LocalizedError, Sendable {
  let message: String
  var status: Int = 0
  var errorDescription: String? { message }
}
func require(_ condition: Bool, _ message: String) throws {
  if !condition { throw ClientFailure(message: message) }
}
