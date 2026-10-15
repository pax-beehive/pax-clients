import Foundation

struct ChatItem: Identifiable, Equatable, Sendable {
  let id: String
  var turn: String
  let role: String
  var text: String
  var title = ""
  var status = ""
}
struct Timeline {
  private(set) var items: [ChatItem] = []
  private var segment = 0
  private var lastChunk = ""
  private static let thoughtKinds: Set<String> = [
    "agent_thought", "agent_thought_chunk", "thought", "thinking", "reasoning", "progress",
  ]
  mutating func clear() { self = Timeline() }
  mutating func upsert(_ item: ChatItem) {
    if let index = items.firstIndex(where: { $0.id == item.id }) {
      items[index] = item
    } else {
      items.append(item)
    }
  }
  mutating func remove(_ id: String) {
    items.removeAll { $0.id == id || $0.id.hasPrefix(id + ":part:") }
  }
  mutating func prompt(_ id: String, turn: String, text: String, status: String = "") {
    upsert(ChatItem(id: "user:" + id, turn: turn, role: "user", text: text, status: status))
    lastChunk = ""
  }
  mutating func status(_ id: String, _ status: String) {
    if let index = items.firstIndex(where: { $0.id == "user:" + id }) {
      items[index].status = status
    }
  }
  mutating func replaceTurn(_ turn: String, messages: [JSON]) {
    items.removeAll { $0.turn == turn }
    for message in messages { history(message) }
    lastChunk = ""
  }
  mutating func prepend(_ older: Timeline) {
    let ids = Set(items.map(\.id))
    items = older.items.filter { !ids.contains($0.id) } + items
  }
  mutating func history(_ value: JSON) {
    let id = value["message_id"].string
    guard !id.isEmpty else { return }
    let tool = value["tool"]
    let parts = value["parts"].array.sorted { $0["part_index"].int < $1["part_index"].int }
    let text = parts.map { $0["text"].string }.joined(separator: "\n")
    let fallback = text.isEmpty ? value["raw_json"]["text"].string : text
    var role = value.text("role", value.text("message_type", "agent"))
    if !tool.object.isEmpty {
      role = "tool"
    } else if Self.thoughtKinds.contains(role.lowercased())
      || Self.thoughtKinds.contains(value["message_type"].string.lowercased())
    {
      role = "thought"
    }
    guard !fallback.isEmpty || !tool.object.isEmpty else { return }
    remove(id)
    var segments: [(String, String)] = []
    if ["agent", "assistant"].contains(role)
      && parts.contains(where: { Self.thoughtKinds.contains($0["part_type"].string.lowercased()) })
    {
      for part in parts where !part["text"].string.isEmpty {
        let partRole =
          Self.thoughtKinds.contains(part["part_type"].string.lowercased()) ? "thought" : role
        if segments.last?.0 == partRole {
          segments[segments.count - 1].1 += "\n" + part["text"].string
        } else {
          segments.append((partRole, part["text"].string))
        }
      }
    } else {
      segments = [(role, fallback)]
    }
    for (index, part) in segments.enumerated() {
      upsert(
        ChatItem(
          id: index == 0 ? id : "\(id):part:\(index)", turn: value["turn_id"].string, role: part.0,
          text: part.1, title: tool["title"].string, status: tool["status"].string))
    }
  }
  mutating func frame(_ frame: JSON, turn: String) {
    let params = frame["params"]
    let update = params["update"] == .null ? frame["update"] : params["update"]
    switch frame["method"].string {
    case "session/prompt":
      prompt(
        frame.text("id", turn), turn: turn,
        text: params["prompt"].array.filter { $0["type"].string == "text" }.map {
          $0["text"].string
        }.joined())
    case "session/update":
      switch update["sessionUpdate"].string {
      case "agent_message_chunk", "agent_thought_chunk", "user_message_chunk":
        let kind = update["sessionUpdate"].string
        let role =
          kind == "user_message_chunk"
          ? "user" : kind == "agent_thought_chunk" ? "thought" : "agent"
        let key = "\(turn):\(role)"
        if lastChunk != key {
          segment += 1
          lastChunk = key
        }
        let id = "\(key):\(segment)"
        let text = update["content"].text("text", update["text"].string)
        if let index = items.firstIndex(where: { $0.id == id }) {
          items[index].text += text
        } else {
          items.append(ChatItem(id: id, turn: turn, role: role, text: text))
        }
      case "tool_call", "tool_call_update":
        lastChunk = ""
        let id = "tool:\(turn):\(update["toolCallId"].string)"
        let old = items.first { $0.id == id }
        let text = update["content"].array.map { $0["content"].text("text", $0["text"].string) }
          .joined(separator: "\n")
        upsert(
          ChatItem(
            id: id, turn: turn, role: "tool", text: text.isEmpty ? old?.text ?? "" : text,
            title: update.text("title", old?.title ?? "Tool call"),
            status: update.text("status", old?.status ?? "")))
      default: break
      }
    case "session/request_permission":
      lastChunk = ""
      upsert(
        ChatItem(
          id: "permission:" + frame["id"].string, turn: turn, role: "approval",
          text: "Review this request in Approvals.",
          title: params["toolCall"].text("title", "Approval required")))
    default: break
    }
  }
}
