#if os(iOS)
  import SwiftUI

  struct NewProjectView: View {
    @Bindable var model: WorkspaceModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var busy = false
    @State private var uncertain = false
    @State private var error: String?
    var body: some View {
      NavigationStack {
        Form {
          Section("Project name") {
            TextField("e.g. My next idea", text: $name).disabled(uncertain)
            Text("Keep related sessions together across your devices.").font(.footnote)
              .foregroundStyle(.secondary)
          }
          if let error {
            Section {
              Text(error).foregroundStyle(.orange)
              if uncertain {
                Text(
                  "Refresh Projects to check whether it was created before starting another request."
                )
                Button("Refresh projects") {
                  Task {
                    await model.refresh()
                    dismiss()
                  }
                }
              }
            }
          }
        }.navigationTitle("New project").navigationBarTitleDisplayMode(.inline)
          .toolbar {
            ToolbarItem(placement: .cancellationAction) {
              Button("Cancel") { dismiss() }.disabled(busy)
            }
            ToolbarItem(placement: .confirmationAction) {
              Button("Create") { create() }.accessibilityIdentifier("create-project").disabled(
                busy || uncertain || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                  || name.trimmingCharacters(in: .whitespacesAndNewlines).utf8.count > 100)
            }
          }.overlay { if busy { ProgressView() } }.interactiveDismissDisabled(busy)
      }
    }
    private func create() {
      busy = true
      Task {
        defer { busy = false }
        do {
          let project = try await model.repo.createProject(name)
          model.workspace.projects.append(project)
          dismiss()
        } catch {
          self.error = error.localizedDescription
          uncertain = true
        }
      }
    }
  }

  struct NewSessionView: View {
    @Bindable var model: WorkspaceModel
    let created: (Session) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var draft = SessionDraft()
    @State private var agents: [Resource] = []
    @State private var targets: [Resource] = []
    @State private var targetID = ""
    @State private var browsing = false
    @State private var busy = false
    @State private var loading = false
    @State private var loadingTargets = false
    @State private var uncertain = false
    @State private var error: String?
    var body: some View {
      NavigationStack {
        Form {
          Section("Workspace") {
            Picker("Project", selection: $draft.projectID) {
              Text("No project").tag("")
              ForEach(model.workspace.projects) { Text($0.title).tag($0.id) }
            }.accessibilityIdentifier("session-project")
            if !targets.isEmpty {
              Picker("Project target", selection: $targetID) {
                Text("Choose manually").tag("")
                ForEach(targets) { Text($0.title).tag($0.id) }
              }
            }
            Picker("Device", selection: $draft.nodeID) {
              Text("Choose device").tag("")
              ForEach(model.workspace.nodes.filter { $0.raw["online"].bool }) {
                Text($0.title).tag($0.id)
              }
            }
            Picker("Agent", selection: $draft.agentID) {
              Text("Choose agent").tag("")
              ForEach(
                agents.filter {
                  $0.raw.text("node_id") == draft.nodeID && $0.raw["online"] != .bool(false)
                }
              ) { Text($0.title).tag($0.id) }
            }
            if loading || loadingTargets { ProgressView("Loading options…") }
            if model.workspace.nodes.allSatisfy({ !$0.raw["online"].bool }) {
              Text("Connect an agent host to create a session.").foregroundStyle(.secondary)
            }
          }.disabled(busy || uncertain || loading || loadingTargets)
          Section("Session") {
            Button("Browse device folders") { browsing = true }.disabled(draft.nodeID.isEmpty)
            TextField("Absolute working directory", text: $draft.cwd).textInputAutocapitalization(
              .never
            ).autocorrectionDisabled()
            TextField("Name (optional)", text: $draft.name)
            Text("The directory is on the selected device.").font(.footnote).foregroundStyle(
              .secondary)
          }.disabled(busy || uncertain)
          if let error {
            Section {
              Text(error).foregroundStyle(.orange)
              if uncertain {
                Button("Check creation status") { submit(recovery: true) }.disabled(busy)
                Text(
                  "The request may have succeeded. Check the same session before creating another."
                ).font(.footnote)
              }
            }
          }
        }.navigationTitle("New session").navigationBarTitleDisplayMode(.inline)
          .toolbar {
            ToolbarItem(placement: .cancellationAction) {
              Button("Cancel") { dismiss() }.disabled(busy)
            }
            ToolbarItem(placement: .confirmationAction) {
              Button("Create") { submit(recovery: false) }.accessibilityIdentifier("create-session")
                .disabled(
                  !draft.valid || busy || loading || loadingTargets || uncertain)
            }
          }.interactiveDismissDisabled(busy)
          .sheet(isPresented: $browsing) {
            let node = draft.nodeID
            DirectoryBrowserView(node: node, repo: model.repo) { path in
              if draft.nodeID == node { draft.cwd = path }
            }
          }
          .task { await loadAgents() }
          .task(id: draft.projectID) { await loadTargets() }
          .onChange(of: draft.nodeID) { old, new in
            if old != new
              && !agents.contains(where: {
                $0.id == draft.agentID && $0.raw["node_id"].string == new
              })
            {
              draft.agentID = ""
              draft.cwd = ""
              targetID = ""
            }
          }
          .onChange(of: targetID) { _, _ in applyTarget() }
      }
    }
    private func loadAgents() async {
      loading = true
      defer { loading = false }
      do {
        var result: [Resource] = []
        for node in model.workspace.nodes where node.raw["online"].bool {
          let values = try await model.repo.get(["nodes", node.id, "agents"])
          try Task.checkCancellation()
          result += values["agents"].array.map { value in
            var raw = value.object
            raw["node_id"] = .string(node.id)
            return Resource(raw: .object(raw), kind: "agent")
          }
        }
        agents = result
        applyTarget()
      } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func loadTargets() async {
      targets = []
      targetID = ""
      loadingTargets = false
      guard !draft.projectID.isEmpty else { return }
      loadingTargets = true
      defer { if !Task.isCancelled { loadingTargets = false } }
      let project = draft.projectID
      do {
        let values = try await model.repo.get(["projects", project, "targets"])
        try Task.checkCancellation()
        guard draft.projectID == project else { return }
        targets = values["targets"].array.filter { $0["enabled"].bool }.map {
          Resource(raw: $0, kind: "target")
        }
        targetID = targets.first { $0.raw["is_default"].bool }?.id ?? ""
        applyTarget()
      } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func applyTarget() {
      guard let target = targets.first(where: { $0.id == targetID }),
        let agent = agents.first(where: {
          $0.id == target.raw["agent_id"].string && $0.raw["online"] != .bool(false)
        })
      else { return }
      draft.nodeID = agent.raw["node_id"].string
      draft.agentID = agent.id
      draft.cwd = target.raw["cwd"].string
    }
    private func submit(recovery: Bool) {
      guard !busy && draft.valid else { return }
      busy = true
      error = nil
      Task {
        defer { busy = false }
        do {
          let result =
            try await
            (recovery
            ? model.repo.get(draft.route + [draft.id]) : model.repo.post(draft.route, draft.body))
          let session = try draft.validate(result, recovery: recovery)
          created(session)
          dismiss()
        } catch {
          self.error = error.localizedDescription
          uncertain = true
        }
      }
    }
  }

  struct ApprovalsView: View {
    @Bindable var model: WorkspaceModel
    @Environment(\.dismiss) private var dismiss
    @State private var working: Set<String> = []
    @State private var submitted: Set<String> = []
    @State private var error: String?
    @State private var decision: (approval: Resource, option: JSON)?
    @State private var confirmDecision = false
    var body: some View {
      NavigationStack {
        List {
          if let error { Text(error).font(.footnote).foregroundStyle(.orange) }
          ForEach(model.workspace.approvals) { approval in
            VStack(alignment: .leading, spacing: 14) {
              Label(approval.title, systemImage: "checkmark.shield").font(.headline)
              Text(approval.raw["description"].string).font(.callout).textSelection(.enabled)
              ForEach(approval.raw["options"].array, id: \.selfID) { option in
                Button(option.text("label", option["option_id"].string)) {
                  decision = (approval, option)
                  confirmDecision = true
                }
                .buttonStyle(.bordered).disabled(
                  working.contains(approval.id) || submitted.contains(approval.id))
              }
              if working.contains(approval.id) { ProgressView() }
              if submitted.contains(approval.id) {
                Text("Decision sent. Refresh to confirm status.").font(.caption).foregroundStyle(
                  .secondary)
              }
            }.padding(.vertical, 8)
          }
        }.overlay {
          if model.workspace.approvals.isEmpty {
            ContentUnavailableView(
              "All clear", systemImage: "checkmark.shield",
              description: Text("Requests that need your decision will appear here."))
          }
        }
        .navigationTitle("Approvals").toolbar {
          ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
        }
        .refreshable { await model.refresh() }.task { await model.refresh() }
        .interactiveDismissDisabled(!working.isEmpty)
        .confirmationDialog(
          "Confirm decision", isPresented: $confirmDecision, titleVisibility: .visible
        ) {
          if let decision {
            Button("Confirm") {
              decide(decision.approval, option: decision.option)
              self.decision = nil
            }
            Button("Cancel", role: .cancel) { self.decision = nil }
          }
        } message: {
          if let decision {
            Text(
              "\(decision.option.text("label", decision.option["option_id"].string)) · \(decision.approval.title)"
            )
          }
        }
      }
    }
    private func decide(_ approval: Resource, option: JSON) {
      guard !working.contains(approval.id) && !submitted.contains(approval.id) else { return }
      working.insert(approval.id)
      submitted.insert(approval.id)
      error = nil
      Task {
        defer { working.remove(approval.id) }
        do {
          try await model.repo.decide(approval, option: option)
          model.workspace.approvals.removeAll { $0.id == approval.id }
          try await model.repo.resume(approval)
          await model.refresh()
        } catch {
          self.error =
            error.localizedDescription
            + " Refresh to inspect the result; this decision will not be sent again automatically."
        }
      }
    }
  }
  extension JSON { fileprivate var selfID: String { self["option_id"].string } }

  struct PairingView: View {
    let pairing: DevicePairing
    let agentID: String
    let authorized: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pending: Pairing?
    @State private var busy = false
    @State private var error: String?
    var body: some View {
      NavigationStack {
        Form {
          Section {
            Label("Your account is signed in", systemImage: "person.crop.circle.badge.checkmark")
            Text(
              "Encrypted sessions need a separate approval from the agent host. Your root key stays in this device’s Keychain."
            )
          }
          if let pending {
            Section("Run on the agent host") {
              Text(DevicePairing.command(pending)).font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
              Button("Copy authorization command") {
                UIPasteboard.general.setItems(
                  [[UIPasteboard.typeAutomatic: DevicePairing.command(pending)]],
                  options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(300)])
              }
              Button("I approved access — check again") { finish(pending) }.disabled(busy)
            }
          } else {
            Button("Prepare authorization") { start() }.disabled(busy)
          }
          if busy { ProgressView() }
          if let error { Text(error).foregroundStyle(.orange) }
        }.navigationTitle("Encryption access").navigationBarTitleDisplayMode(.inline)
          .toolbar {
            ToolbarItem(placement: .confirmationAction) {
              Button("Done") { dismiss() }.disabled(busy)
            }
          }.interactiveDismissDisabled(busy)
      }
    }
    private func start() {
      busy = true
      error = nil
      Task {
        defer { busy = false }
        do { pending = try await pairing.start(agentID) } catch {
          self.error = error.localizedDescription
        }
      }
    }
    private func finish(_ p: Pairing) {
      busy = true
      error = nil
      Task {
        defer { busy = false }
        do {
          try await pairing.finish(p)
          authorized()
          dismiss()
        } catch { self.error = error.localizedDescription }
      }
    }
  }

  struct SettingsView: View {
    @Bindable var model: WorkspaceModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmSignOut = false
    private var acknowledgements: String {
      guard let url = Bundle.module.url(forResource: "acknowledgements", withExtension: "txt")
      else { return "" }
      return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
    }
    var body: some View {
      NavigationStack {
        Form {
          Section("Account") {
            LabeledContent("Email", value: model.account?.email ?? "")
            LabeledContent("Region", value: model.account?.region.uppercased() ?? "")
            LabeledContent("Security", value: "System sign-in · Keychain")
          }
          Section("Devices") {
            ForEach(model.workspace.nodes) { node in
              NavigationLink {
                NodeToolsView(node: node, repo: model.repo)
              } label: {
                HStack {
                  Image(
                    systemName: node.raw["os"].string == "darwin"
                      ? "laptopcomputer" : "desktopcomputer"
                  ).foregroundStyle(.secondary)
                  VStack(alignment: .leading) {
                    Text(node.title)
                    Text("paxd " + node.raw.text("paxd_version", "version unknown")).font(.caption)
                      .foregroundStyle(.secondary)
                    if !node.raw["hostname"].string.isEmpty {
                      Text(node.raw["hostname"].string).font(.caption).foregroundStyle(.secondary)
                    }
                  }
                  Spacer()
                  Text(node.raw["online"].bool ? "Online" : "Offline").font(.caption)
                    .foregroundStyle(
                      node.raw["online"].bool ? .green : .secondary)
                }
              }
            }
            if model.workspace.nodes.isEmpty {
              Text("No devices connected").foregroundStyle(.secondary)
            }
          }
          Section("About") {
            LabeledContent("PaxWorkspace", value: "iOS development preview")
            NavigationLink("Acknowledgements") {
              ScrollView {
                Text(acknowledgements).font(.footnote).textSelection(.enabled).padding()
              }.navigationTitle("Acknowledgements")
            }
            Text("Native SwiftUI client for iPhone and iPad.").font(.footnote).foregroundStyle(
              .secondary)
          }
          if let error = model.error { Text(error).foregroundStyle(.orange) }
          Section {
            Button("Sign out", role: .destructive) { confirmSignOut = true }.disabled(model.loading)
          }
        }.navigationTitle("Settings").toolbar {
          ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
        }
        .confirmationDialog(
          "Sign out of this device?", isPresented: $confirmSignOut, titleVisibility: .visible
        ) {
          Button("Sign out", role: .destructive) {
            Task {
              await model.signOut()
              if model.account == nil { dismiss() }
            }
          }
        } message: {
          Text("Saved login and encryption keys will be removed from this device.")
        }
      }
    }
  }
#endif
