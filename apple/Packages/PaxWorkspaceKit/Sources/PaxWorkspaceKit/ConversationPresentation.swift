import Foundation

struct ConversationEntry: Identifiable, Equatable {
  var messages: [ChatItem]
  var id: String { messages[0].id }
  var isActivity: Bool { ["tool", "thought"].contains(messages[0].role) }
  var status: String {
    let states = messages.filter { $0.role == "tool" }.map { $0.status.lowercased() }
    if states.contains(where: { ["failed", "error"].contains($0) }) { return "Failed" }
    if states.contains(where: { ["in_progress", "running", "streaming"].contains($0) }) {
      return "Running"
    }
    if states.contains(where: { ["pending", "queued"].contains($0) }) { return "Pending" }
    if states.contains(where: { ["cancelled", "canceled"].contains($0) }) { return "Cancelled" }
    if !states.isEmpty && states.allSatisfy({ ["completed", "done", "success"].contains($0) }) {
      return "Done"
    }
    return ""
  }
  static func grouped(_ messages: [ChatItem]) -> [Self] {
    var result: [Self] = []
    for message in messages {
      if ["tool", "thought"].contains(message.role), let last = result.last, last.isActivity,
        last.messages.last?.turn == message.turn
      {
        result[result.count - 1].messages.append(message)
      } else {
        result.append(Self(messages: [message]))
      }
    }
    return result
  }
}
extension Session {
  var agentName: String {
    switch agentType.lowercased().trimmingCharacters(in: .whitespaces) {
    case "codex", "codex-acp": "Codex"
    case "claude", "claude-code", "claude_code", "claude-agent-acp": "Claude"
    case "gemini": "Gemini"
    case "pi", "pi-acp": "Pi"
    case "kiro": "Kiro"
    case "": "Assistant"
    default: agentType
    }
  }
}
#if os(iOS)
  import SwiftUI
  struct ConversationActivity: View {
    let entry: ConversationEntry
    var body: some View {
      DisclosureGroup {
        ForEach(entry.messages) { item in
          DisclosureGroup {
            ScrollView {
              Text(item.text.isEmpty ? "No additional details." : item.text)
                .font(item.role == "tool" ? .system(.footnote, design: .monospaced) : .footnote)
                .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading).padding(10)
            }.frame(maxHeight: 240)
          } label: {
            Text(
              item.role == "thought"
                ? "Reasoning" : item.title.isEmpty ? "Tool operation" : item.title
            )
            .font(.callout)
          }.padding(.leading, 8)
        }
      } label: {
        VStack(alignment: .leading, spacing: 4) {
          Text("Activity · \(entry.messages.count) \(entry.messages.count == 1 ? "step" : "steps")")
            .font(.callout.weight(.medium))
          Text(
            [entry.messages.last(where: { $0.role == "tool" })?.title ?? "Reasoning", entry.status]
              .filter { !$0.isEmpty }.joined(separator: " · ")
          ).font(.caption).foregroundStyle(.secondary)
        }
      }.padding(12).background(
        Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12)
      )
      .accessibilityIdentifier("activity-group:\(entry.id)")
    }
  }
  struct SessionDetails: View {
    let session: Session
    let device: String
    @Environment(\.dismiss) private var dismiss
    var body: some View {
      NavigationStack {
        Form {
          LabeledContent("Agent", value: session.agentName)
          LabeledContent("Device", value: device)
        }.textSelection(.enabled).navigationTitle("Session details")
          .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
      }.presentationDetents([.medium, .large])
    }
  }
#endif
