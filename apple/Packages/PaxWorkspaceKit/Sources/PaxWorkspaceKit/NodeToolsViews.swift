#if os(iOS)
  import SwiftUI

  struct DirectoryBrowserView: View {
    @State private var browser: DirectoryBrowser
    let selected: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    init(node: String, repo: WorkspaceRepository, selected: @escaping (String) -> Void) {
      _browser = State(initialValue: DirectoryBrowser(node: node, repo: repo))
      self.selected = selected
    }
    var body: some View {
      NavigationStack {
        List {
          Section {
            Text(browser.page?.path ?? "Home (~)").font(.footnote).textSelection(.enabled)
            HStack {
              Button("Home") { browser.load() }
              Spacer()
              Button("Up") { browser.load(browser.page?.parent ?? "") }.disabled(
                browser.page?.parent == nil)
            }.buttonStyle(.borderless).disabled(browser.loading)
            Toggle("Show hidden folders", isOn: $browser.hidden).disabled(browser.loading)
              .onChange(of: browser.hidden) { _, _ in browser.load(browser.page?.path ?? "") }
            Text("Folders inside this device's home only.").font(.footnote).foregroundStyle(
              .secondary)
          }
          if browser.loading { ProgressView("Loading folders…") }
          if let error = browser.error {
            Section {
              Text(error).foregroundStyle(.orange)
              Button("Retry") { browser.retry() }
            }
          }
          Section("Folders") {
            ForEach(browser.page?.entries ?? []) { entry in
              Button {
                browser.load(entry.path)
              } label: {
                HStack {
                  Image(systemName: "folder")
                  Text(entry.name)
                  Spacer()
                  Image(systemName: "chevron.right")
                }
              }.disabled(browser.loading).accessibilityIdentifier("folder:" + entry.path)
            }
            if browser.page?.entries.isEmpty == true && !browser.loading && browser.error == nil {
              Text("No subfolders").foregroundStyle(.secondary)
            }
            if let after = browser.page?.next_after, !after.isEmpty {
              Button("Load more folders") { browser.load(browser.page!.path, after: after) }
                .disabled(browser.loading)
            }
          }
        }.navigationTitle("Choose folder").navigationBarTitleDisplayMode(.inline)
          .safeAreaInset(edge: .bottom) {
            Button("Select this folder") {
              if let path = browser.page?.path {
                selected(path)
                dismiss()
              }
            }.buttonStyle(.borderedProminent).padding().disabled(
              browser.page == nil || browser.loading || browser.error != nil)
          }
          .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
          }
          .task { browser.load() }.onDisappear { browser.cancel() }
      }
    }
  }

  struct NodeToolsView: View {
    @State private var tools: NodeToolsModel
    @State private var confirm = false
    init(node: Resource, repo: WorkspaceRepository) {
      _tools = State(initialValue: NodeToolsModel(node: node, repo: repo))
    }
    private var upToDate: Bool {
      !tools.latest.isEmpty
        && tools.latest.trimmingCharacters(in: CharacterSet(charactersIn: "v"))
          == tools.node.raw["paxd_version"].string.trimmingCharacters(
            in: CharacterSet(charactersIn: "v"))
    }
    var body: some View {
      Form {
        Section("Device") {
          LabeledContent("Status", value: tools.node.raw["online"].bool ? "Online" : "Offline")
          LabeledContent("paxd", value: tools.node.raw.text("paxd_version", "Version unknown"))
          if !tools.node.raw["online"].bool {
            Text("Showing the last reported version.").font(.footnote)
          }
          if !tools.latest.isEmpty { LabeledContent("Latest stable", value: tools.latest) }
        }
        Section {
          if tools.loading { ProgressView() }
          if !tools.message.isEmpty { Text(tools.message) }
          if let error = tools.error { Text(error).foregroundStyle(.orange) }
          if let pending = tools.pending {
            Text("Target: \(pending.version). You can leave this page and check later.")
            Button("Check status") { tools.check() }.disabled(tools.loading)
          } else if tools.error != nil {
            Button("Retry") { tools.load() }
          } else {
            Button(upToDate ? "Up to date" : "Upgrade paxd") { confirm = true }
              .disabled(
                tools.loading || tools.latest.isEmpty || upToDate || !tools.node.raw["online"].bool)
          }
        }
      }.navigationTitle(tools.node.title).navigationBarTitleDisplayMode(.inline)
        .task { tools.load() }.onDisappear { tools.cancel() }
        .alert("Upgrade to \(tools.latest)?", isPresented: $confirm) {
          Button("Upgrade when idle") { tools.upgrade() }
          Button("Cancel", role: .cancel) {}
        } message: {
          Text(
            "The update waits for active work to finish. This device will disconnect briefly while paxd restarts."
          )
        }
    }
  }
#endif
