#if DEBUG && os(iOS) && targetEnvironment(simulator)
  import Foundation

  // Isolated simulator-only harness: never reads Keychain or sends network traffic.
  // Not present in Release builds or any physical-device build.
  @MainActor final class PreviewVault: Vault {
    var value = VaultData(session: UserSession(subject: "user_fixture", refreshToken: "synthetic"))
    func read() throws -> VaultData { value }
    func save(_ value: VaultData) throws { self.value = value }
  }
  actor PreviewTransport: APITransport {
    private var sessions: [JSON] = [
      .fields([
        "session_id": "session_fixture", "node_id": "node_fixture", "agent_id": "agent_fixture",
        "agent_type": "claude", "name": "Build something great",
        "preview": "A native workspace, wherever you are.", "runtime_status": "idle",
        "transport": "manager", "primary_project_id": "project_fixture",
      ]),
      .fields([
        "session_id": "session_encrypted", "node_id": "node_fixture", "agent_id": "agent_fixture",
        "agent_type": "codex", "name": "Private research",
        "preview": "Encrypted session · authorize this device", "runtime_status": "idle",
        "transport": "e2ee",
      ]),
    ]
    private var projects: [JSON] = [
      .fields(["project_id": "project_fixture", "display_name": "PaxWorkspace"])
    ]
    private var approval = true
    private var messages: [JSON] = [
      .object([
        "message_id": .string("m1"), "turn_id": .string("turn_old"), "session_seq": .number(1),
        "role": .string("user"),
        "parts": .array([.fields(["text": "Help me plan the iOS workspace."])]),
      ]),
      .object([
        "message_id": .string("m2"), "turn_id": .string("turn_old"), "session_seq": .number(2),
        "role": .string("assistant"),
        "parts": .array([
          .fields([
            "text":
              "## A workspace that feels at home\n\nYour agents stay on your devices. Continue the conversation from **iPhone or iPad**.\n\n- [x] Immediate send feedback\n- [x] Live streaming replies\n- [ ] Try it with your own account\n\n```swift\nlet workspace = PaxWorkspace()\nawait workspace.connect()\n```\n\n| Device | Experience |\n| --- | --- |\n| iPhone | Focused conversation |\n| iPad | Sessions beside your work |\n\nThis is clearly marked synthetic preview data."
          ])
        ]),
      ]),
    ]
    func json(_ request: URLRequest) async throws -> JSON {
      let path = request.url!.path
      let body = try request.httpBody.map(JSON.parse) ?? .null
      if path.hasSuffix("authenticate") {
        let claims = try JSON.object([
          "sub": .string("user_fixture"),
          "exp": .number(Date().addingTimeInterval(3600).timeIntervalSince1970),
        ]).data()
        return .object([
          "access_token": .string("synthetic." + UserAuth.base64URL(claims) + ".synthetic"),
          "refresh_token": .string("synthetic"), "user": .fields(["id": "user_fixture"]),
        ])
      }
      if path.hasSuffix("bootstrap") {
        return .fields([
          "status": "ready", "api_url": UserAuth.apiOrigin, "user_id": "usr_fixture",
          "email": "preview@example.invalid", "region": "us",
        ])
      }
      if path.hasSuffix("logout") { return .object([:]) }
      if path.hasSuffix("projects") {
        if request.httpMethod == "POST" {
          let p = JSON.fields([
            "project_id": "project_" + UUID().uuidString,
            "display_name": body["display_name"].string,
          ])
          projects.append(p)
          return .object(["project": p])
        }
        return .object(["projects": .array(projects)])
      }
      if path.hasSuffix("nodes") {
        return .object([
          "nodes": .array([
            .object([
              "node_id": .string("node_fixture"), "name": .string("Studio Mac"),
              "hostname": .string("studio.local"), "os": .string("darwin"), "online": .bool(true),
            ])
          ])
        ])
      }
      if path.hasSuffix("agents") {
        return .object([
          "agents": .array([
            .fields(["agent_id": "agent_fixture", "name": "Claude", "agent_type": "claude"])
          ])
        ])
      }
      if path.hasSuffix("targets") {
        return .object([
          "targets": .array([
            .object([
              "target_id": .string("target_fixture"), "agent_id": .string("agent_fixture"),
              "display_name": .string("Studio workspace"), "cwd": .string("/Users/preview/work"),
              "enabled": .bool(true), "is_default": .bool(true),
            ])
          ])
        ])
      }
      if path.hasSuffix("decision") {
        approval = false
        return .object([:])
      }
      if path.hasSuffix("approvals") {
        return .object([
          "approvals": .array(
            approval
              ? [
                .object([
                  "approval_id": .string("approval_fixture"),
                  "title": .string("Run workspace checks"),
                  "description": .string("Allow the agent to run the local test suite?"),
                  "options": .array([
                    .fields(["option_id": "allow_once", "label": "Allow once"]),
                    .fields(["option_id": "deny", "label": "Deny"]),
                  ]),
                ])
              ] : [])
        ])
      }
      if path.hasSuffix("sessions") {
        if request.httpMethod == "POST" {
          var raw = body.object
          raw["node_id"] = .string("node_fixture")
          raw["agent_id"] = .string("agent_fixture")
          raw["transport"] = .string("manager")
          raw["agent_type"] = .string("claude")
          let session = JSON.object(raw)
          sessions.insert(session, at: 0)
          return session
        }
        return .object(["sessions": .array(sessions)])
      }
      if path.hasSuffix("history") {
        return .object([
          "messages": .array(messages), "pagination": .object(["has_older": .bool(false)]),
        ])
      }
      if let s = sessions.first(where: { path.hasSuffix($0["session_id"].string) }) { return s }
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
      func emit(_ value: JSON) throws {
        continuation.yield(
          ServerEvent(id: "", data: String(decoding: try value.data(), as: UTF8.self)))
      }
      if request.url!.path.hasSuffix("conversation") {
        let body = try JSON.parse(request.httpBody!)
        let text = body["input"].string
        let turn = "turn_" + UUID().uuidString
        try emit(
          .fields([
            "type": "turn_started", "turn_id": turn, "session_id": body["session_id"].string,
          ]))
        let answer = "Message received. Your iOS workspace is streaming this reply in real time."
        for word in answer.split(separator: " ") {
          try await Task.sleep(for: .milliseconds(100))
          try emit(
            .object([
              "type": .string("acp"), "turn_id": .string(turn),
              "frame": .object([
                "method": .string("session/update"),
                "params": .object([
                  "update": .object([
                    "sessionUpdate": .string("agent_message_chunk"),
                    "content": .fields(["type": "text", "text": String(word) + " "]),
                  ])
                ]),
              ]),
            ]))
        }
        messages += [
          message(text, role: "user", turn: turn), message(answer, role: "assistant", turn: turn),
        ]
        try emit(.fields(["type": "done"]))
      } else {
        try emit(.fields(["type": "no_running_turn"]))
        try await Task.sleep(for: .seconds(60))
      }
    }
    private func message(_ text: String, role: String, turn: String) -> JSON {
      .object([
        "message_id": .string(UUID().uuidString), "turn_id": .string(turn), "role": .string(role),
        "session_seq": .number(Double(messages.count + (role == "user" ? 1 : 2))),
        "parts": .array([.fields(["text": text])]),
      ])
    }
  }
#endif
