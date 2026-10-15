import SwiftUI

#if os(iOS)
  import AuthenticationServices

  public struct WorkspaceShell: View {
    @State private var model: WorkspaceModel
    @State private var entered = false
    @State private var browser = BrowserSignIn()
    @Environment(\.scenePhase) private var scenePhase
    public init() {
      #if DEBUG && targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains("--pax-keychain-fixture") {
          let vault = SecureVault(service: "net.paxtech.workspace.uitests.synthetic")
          if !ProcessInfo.processInfo.arguments.contains("--pax-keychain-read-only")
            && (try? vault.read().session) == nil
          {
            try? vault.save(
              VaultData(session: UserSession(subject: "user_fixture", refreshToken: "synthetic")))
          }
          _model = State(
            initialValue: WorkspaceModel(api: PreviewTransport(), vault: vault, preview: true))
        } else if ProcessInfo.processInfo.arguments.contains("--pax-ui-fixture") {
          _model = State(
            initialValue: WorkspaceModel(
              api: PreviewTransport(), vault: PreviewVault(), preview: true))
        } else {
          _model = State(initialValue: WorkspaceModel())
        }
      #else
        _model = State(initialValue: WorkspaceModel())
      #endif
    }
    public var body: some View {
      Group {
        if model.restoring {
          ProgressView("Opening workspace…").frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if model.account != nil
          && (entered || skipWelcomeForPreview)
        {
          WorkspaceHome(model: model)
        } else if model.needsRegion {
          regionSelection
        } else {
          WelcomeView(
            signedIn: model.account != nil, loading: model.loading, error: model.error,
            signIn: {
              if model.account != nil { entered = true } else { signIn() }
            },
            retry: { Task { await model.restore() } })
        }
      }
      .onChange(of: model.account == nil) { _, signedOut in if signedOut { entered = false } }
      .tint(.indigo)
      .task { await model.restore() }
      .onOpenURL { url in Task { await model.receiveCallback(url) } }
      .onChange(of: scenePhase) { _, phase in
        if phase == .background { model.runtime?.pause() }
        if phase == .active && model.account != nil {
          model.runtime?.connect()
          Task { await model.refresh() }
        }
      }
    }
    private var skipWelcomeForPreview: Bool {
      #if DEBUG && targetEnvironment(simulator)
        model.isPreview && !ProcessInfo.processInfo.arguments.contains("--pax-welcome-fixture")
      #else
        false
      #endif
    }
    private var regionSelection: some View {
      VStack(spacing: 24) {
        Image(systemName: "globe").font(.system(size: 56)).foregroundStyle(.indigo)
        Text("Choose your region").font(.largeTitle.bold())
        Text("Your projects and agents will use this workspace region.").foregroundStyle(.secondary)
          .multilineTextAlignment(.center)
        Button("United States") { Task { await model.chooseRegion("us") } }.buttonStyle(
          .borderedProminent)
        Button("Hong Kong") { Task { await model.chooseRegion("hk") } }.buttonStyle(.bordered)
        if model.loading { ProgressView() }
        if let error = model.error { Text(error).font(.callout).foregroundStyle(.red) }
        Button("Sign out") { Task { await model.signOut() } }
      }.padding(32).disabled(model.loading)
    }
    private func signIn() {
      guard !model.loading else { return }
      do {
        let url = try model.auth.start()
        model.loading = true
        model.error = nil
        browser.start(url: url) { callback, error in
          if let callback {
            Task { await model.receiveCallback(callback) }
          } else {
            model.loading = false
            try? model.auth.cancel()
            if (error as? ASWebAuthenticationSessionError)?.code != .canceledLogin {
              model.error = error?.localizedDescription
            }
          }
        }
      } catch { model.error = error.localizedDescription }
    }
  }

  private struct WorkspaceHome: View {
    @Bindable var model: WorkspaceModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var section = WorkspaceSection.sessions
    @State private var selection: String?
    @State private var search = ""
    @State private var projectFilter = ""
    @State private var showSession = false
    @State private var showProject = false
    @State private var showApprovals = false
    @State private var showSettings = false
    private var sessions: [Session] {
      model.workspace.sessions.filter {
        (projectFilter.isEmpty || $0.projectID == projectFilter)
          && (search.isEmpty || $0.title.localizedCaseInsensitiveContains(search)
            || $0.raw["preview"].string.localizedCaseInsensitiveContains(search))
      }
    }
    var body: some View {
      NavigationSplitView {
        VStack(spacing: 0) {
          if model.isPreview { PreviewBadge() }
          Picker("Home view", selection: $section) {
            Text("Sessions").tag(WorkspaceSection.sessions)
            Text("Projects").tag(WorkspaceSection.projects)
          }.pickerStyle(.segmented).padding(.horizontal).padding(.bottom, 8)
          if let error = model.error { ErrorBanner(text: error) { Task { await model.refresh() } } }
          if section == .sessions { sessionList } else { projectList }
        }
        .navigationTitle("Workspace")
        .searchable(
          text: $search, prompt: section == .sessions ? "Search sessions" : "Search projects"
        )
        .toolbar {
          ToolbarItem(placement: .topBarLeading) {
            Button {
              showSettings = true
            } label: {
              Image(systemName: "person.crop.circle")
            }.accessibilityLabel("Settings and devices")
          }
          ToolbarItemGroup(placement: .topBarTrailing) {
            Button {
              showApprovals = true
            } label: {
              Image(
                systemName: model.workspace.approvals.isEmpty
                  ? "checkmark.shield" : "checkmark.shield.fill")
            }.accessibilityLabel("Approvals, \(model.workspace.approvals.count) requests")
            Menu {
              Button("New session", systemImage: "square.and.pencil") { showSession = true }
              Button("New project", systemImage: "folder.badge.plus") { showProject = true }
            } label: {
              Image(systemName: "plus")
            }.accessibilityLabel("Create").accessibilityIdentifier("create-menu")
          }
        }
        .navigationSplitViewColumnWidth(min: 280, ideal: 340, max: 420)
      } detail: {
        if let runtime = model.runtime {
          ConversationView(model: model, runtime: runtime).id(runtime.session.id)
        } else {
          ContentUnavailableView(
            "Your agents, within reach", systemImage: "bubble.left.and.bubble.right",
            description: Text("Choose a session, or start something new."))
        }
      }
      .navigationSplitViewStyle(.balanced)
      .onChange(of: selection) { _, id in
        model.select(model.workspace.sessions.first { $0.id == id })
      }
      .sheet(isPresented: $showSession) {
        NewSessionView(model: model) { session in
          model.workspace.sessions.insert(session, at: 0)
          section = .sessions
          selection = session.id
          model.select(session)
        }
      }
      .sheet(isPresented: $showProject) { NewProjectView(model: model) }
      .sheet(isPresented: $showApprovals) { ApprovalsView(model: model) }
      .sheet(isPresented: $showSettings) { SettingsView(model: model) }
      .task(id: scenePhase) {
        guard scenePhase == .active else { return }
        while !Task.isCancelled {
          try? await Task.sleep(for: .seconds(20))
          if !Task.isCancelled { await model.refresh() }
        }
      }
    }
    private var sessionList: some View {
      List(selection: $selection) {
        if !projectFilter.isEmpty {
          Button {
            projectFilter = ""
          } label: {
            Label("All sessions", systemImage: "line.3.horizontal.decrease.circle")
          }
        }
        ForEach(sessions) { session in
          NavigationLink(value: session.id) {
            SessionRow(session: session, device: model.deviceName(session))
          }
        }
        if model.workspace.hasMore {
          Button(model.refreshing ? "Loading…" : "Load more sessions") {
            Task { await model.refresh(more: true) }
          }.disabled(model.refreshing)
        }
      }
      .listStyle(.plain)
      .overlay {
        if sessions.isEmpty {
          ContentUnavailableView(
            search.isEmpty ? "No sessions yet" : "No matching sessions",
            systemImage: "bubble.left.and.bubble.right",
            description: Text("Start a session on one of your connected devices."))
        }
      }
      .refreshable { await model.refresh() }
    }
    private var projectList: some View {
      List {
        ForEach(
          model.workspace.projects.filter {
            search.isEmpty || $0.title.localizedCaseInsensitiveContains(search)
          }
        ) { project in
          Button {
            projectFilter = project.id
            search = ""
            section = .sessions
          } label: {
            HStack(spacing: 14) {
              Image(systemName: "folder").font(.title2).foregroundStyle(.indigo).frame(width: 36)
              VStack(alignment: .leading, spacing: 5) {
                Text(project.title).font(.headline).foregroundStyle(.primary)
                Text("View project sessions").font(.caption).foregroundStyle(.secondary)
              }
              Spacer()
              Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
            }.padding(.vertical, 9)
          }
        }
      }.listStyle(.plain).refreshable { await model.refresh() }
        .overlay {
          if model.workspace.projects.isEmpty {
            ContentUnavailableView(
              "A place for your work", systemImage: "folder",
              description: Text("Create a project to keep related sessions together."))
          }
        }
    }
  }

  struct SessionRow: View {
    let session: Session
    let device: String
    var body: some View {
      VStack(alignment: .leading, spacing: 9) {
        HStack(alignment: .firstTextBaseline) {
          Text(session.title).font(.headline).lineLimit(2)
          Spacer(minLength: 8)
          if session.encrypted {
            Image(systemName: "lock.fill").font(.caption2).foregroundStyle(.secondary)
              .accessibilityLabel("Encrypted")
          }
          Circle().fill(
            ["running", "waiting_approval"].contains(session.status)
              ? Color.orange : Color.secondary.opacity(0.45)
          ).frame(width: 6, height: 6).accessibilityLabel(session.status)
        }
        if !session.raw["preview"].string.isEmpty {
          Text(session.raw["preview"].string).font(.subheadline).foregroundStyle(.secondary)
            .lineLimit(2)
        }
        HStack(spacing: 5) {
          Text(session.agentName)
          Text("·")
          Text(device)
        }.font(.caption).foregroundStyle(.tertiary).lineLimit(1)
      }.padding(.vertical, 10)
    }
  }
  struct PreviewBadge: View {
    var body: some View {
      Text("PREVIEW · SYNTHETIC DATA").font(.caption2.monospaced()).dynamicTypeSize(
        ...DynamicTypeSize.large
      ).foregroundStyle(.secondary)
        .frame(maxWidth: .infinity).padding(4)
    }
  }
  struct ErrorBanner: View {
    let text: String
    var retry: (() -> Void)?
    var body: some View {
      HStack(alignment: .top, spacing: 10) {
        Image(systemName: "exclamationmark.circle").foregroundStyle(.orange)
        Text(text).font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
        if let retry { Button("Retry", action: retry).font(.footnote.bold()) }
      }.padding(12).background(.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal)
    }
  }
#else
  public struct WorkspaceShell: View {
    public init() {}
    public var body: some View {
      Text("PaxWorkspace is available on iOS. The shared package contains its native runtime.")
        .padding()
    }
  }
#endif
