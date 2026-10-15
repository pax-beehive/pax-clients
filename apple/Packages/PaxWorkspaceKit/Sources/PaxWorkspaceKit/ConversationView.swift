#if os(iOS)
  import SwiftUI

  struct ConversationView: View {
    @Bindable var model: WorkspaceModel
    @Bindable var runtime: SessionRuntime
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var motion = SendMotion()
    @State private var followTail = true
    @State private var viewportHeight: CGFloat = 0
    @State private var showDetails = false
    @State private var showPairing = false
    @State private var showApprovals = false
    @State private var stopping = false
    @FocusState private var composing: Bool
    var body: some View {
      VStack(spacing: 0) {
        if model.isPreview { PreviewBadge() }
        Group {
          if typeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 2) {
              Text(
                "\(runtime.session.agentName) · \(model.deviceName(runtime.session))"
              )
              Text(runtime.connection).foregroundStyle(
                runtime.connection == "Connected" ? .green : .orange)
            }.frame(maxWidth: .infinity, alignment: .leading)
          } else {
            HStack(spacing: 6) {
              Circle().fill(runtime.connection == "Connected" ? .green : .orange).frame(
                width: 5, height: 5)
              Text(
                runtime.session.agentName)
              Text("·")
              Text(model.deviceName(runtime.session))
              Spacer()
              Text(runtime.connection).lineLimit(1)
            }
          }
        }.font(.caption).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.vertical, 8)
        if let error = runtime.error {
          ErrorBanner(
            text: error,
            retry: runtime.connection == "Needs reconnect" || runtime.connection == "Unavailable"
              ? { runtime.connect() } : nil)
        }
        if runtime.locked {
          ContentUnavailableView {
            Label("Authorize this device", systemImage: "lock.shield")
          } description: {
            Text(
              "This session is encrypted. Approve key access from its agent host to continue on this iPhone or iPad."
            )
          } actions: {
            Button("Authorize key access") { showPairing = true }.buttonStyle(.borderedProminent)
          }
        } else {
          timeline
          composer.fixedSize(horizontal: false, vertical: true)
        }
      }
      .coordinateSpace(name: "send-host")
      .overlay {
        SendFlightOverlay(motion: motion).allowsHitTesting(false).accessibilityHidden(true)
      }
      .onPreferenceChange(SendGeometry.self) { motion.place($0, messages: runtime.messages) }
      .onChange(of: runtime.messages) { _, messages in motion.observe(messages) }
      .onChange(of: reduceMotion) { _, _ in motion.finish() }
      .onChange(of: typeSize) { _, _ in motion.finish() }
      .onDisappear { motion.finish() }
      .background(Color(uiColor: .systemBackground))
      .navigationTitle(runtime.session.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .topBarTrailing) {
          Menu {
            Button("Session details", systemImage: "info.circle") { showDetails = true }
            Button("Reconnect", systemImage: "arrow.clockwise") { runtime.connect() }
            Button("Approvals", systemImage: "checkmark.shield") { showApprovals = true }
            if runtime.session.encrypted {
              Button("Encryption access", systemImage: "lock") { showPairing = true }
            }
          } label: {
            Image(systemName: "ellipsis")
          }.accessibilityLabel("Session actions")
        }
      }
      .sheet(isPresented: $showDetails) {
        SessionDetails(session: runtime.session, device: model.deviceName(runtime.session))
      }
      .sheet(isPresented: $showPairing) {
        PairingView(pairing: model.pairing, agentID: runtime.session.agentID) { runtime.connect() }
      }
      .sheet(isPresented: $showApprovals) { ApprovalsView(model: model) }
    }
    private var timeline: some View {
      ScrollViewReader { proxy in
        ScrollView {
          LazyVStack(alignment: .leading, spacing: 22) {
            if runtime.hasOlder {
              Button(runtime.loadingOlder ? "Loading…" : "Load earlier messages") {
                followTail = false
                Task { await runtime.loadOlder() }
              }.font(.footnote).disabled(runtime.loadingOlder || runtime.busy).frame(
                maxWidth: .infinity)
            }
            if runtime.messages.isEmpty && !runtime.busy {
              ContentUnavailableView(
                "What are we working on?", systemImage: "sparkle",
                description: Text("Send a message to continue with your agent.")
              ).padding(.top, 64)
            }
            ForEach(ConversationEntry.grouped(runtime.messages)) { entry in
              if entry.isActivity {
                ConversationActivity(entry: entry)
              } else {
                MessageRow(item: entry.messages[0], inFlight: motion.matches(entry.messages[0]))
              }
            }
            if runtime.busy && runtime.messages.last?.role == "user" {
              ThinkingIndicator().padding(.leading, 2)
            }
            GeometryReader { geometry in
              Color.clear.preference(
                key: TailPosition.self,
                value: geometry.frame(in: .named("conversation-scroll")).maxY)
            }.frame(height: 1).id("tail")
          }.padding(.horizontal, 20).padding(.vertical, 20).frame(maxWidth: 840).frame(
            maxWidth: .infinity)
        }
        .sendGeometry("viewport")
        .coordinateSpace(name: "conversation-scroll")
        .accessibilityIdentifier("conversation-timeline")
        .background {
          GeometryReader { geometry in
            Color.clear.onAppear { viewportHeight = geometry.size.height }.onChange(
              of: geometry.size.height
            ) { _, height in
              viewportHeight = height
              if followTail {
                Task { @MainActor in
                  await Task.yield()
                  proxy.scrollTo("tail", anchor: .bottom)
                }
              }
            }
          }
        }
        .onPreferenceChange(TailPosition.self) { position in
          if position > 0 && position <= viewportHeight + 32 { followTail = true }
        }
        .simultaneousGesture(DragGesture(minimumDistance: 8).onChanged { _ in followTail = false })
        .scrollDismissesKeyboard(.interactively)
        .defaultScrollAnchor(.bottom)
        .overlay(alignment: .bottomTrailing) {
          if !followTail && !runtime.messages.isEmpty {
            Button {
              followTail = true
              proxy.scrollTo("tail", anchor: .bottom)
            } label: {
              Image(systemName: "arrow.down").padding(12).background(.regularMaterial, in: Circle())
                .shadow(radius: 4)
            }.padding().accessibilityLabel("Jump to latest message")
          }
        }
        .onChange(of: runtime.messages) { old, new in
          if followTail || (new.last?.role == "user" && old.last?.id != new.last?.id) {
            Task { @MainActor in
              await Task.yield()
              proxy.scrollTo("tail", anchor: .bottom)
            }
          }
        }
        .onChange(of: composing) { _, focused in
          if focused && followTail { proxy.scrollTo("tail", anchor: .bottom) }
        }
      }
    }
    private var composer: some View {
      HStack(alignment: .bottom, spacing: 12) {
        TextField("Message your agent…", text: $model.draft, axis: .vertical)
          .lineLimit(1...(typeSize.isAccessibilitySize ? 3 : 7)).padding(.vertical, 11).padding(
            .leading, 16
          ).focused($composing)
          .accessibilityIdentifier("message-composer")
        if runtime.busy {
          Button {
            stopping = true
            Task {
              await runtime.stop()
              stopping = false
            }
          } label: {
            Image(systemName: "stop.fill").font(.system(size: 14, weight: .bold)).frame(
              width: 38, height: 38)
          }
          .buttonStyle(.borderedProminent).buttonBorderShape(.circle).tint(.primary).disabled(
            stopping
          ).accessibilityLabel("Stop response")
        } else {
          Button {
            let text = model.draft
            if runtime.send(text) {
              if let item = runtime.messages.last(where: { $0.role == "user" }) {
                motion.launch(
                  item,
                  enabled: SendMotionPolicy.allows(
                    text, reducedMotion: reduceMotion,
                    accessibilityText: typeSize.isAccessibilitySize))
              }
              model.draft = ""
              followTail = true
            }
          } label: {
            Image(systemName: "arrow.up").font(.system(size: 18, weight: .semibold)).frame(
              width: 38, height: 38)
          }
          .buttonStyle(.borderedProminent).buttonBorderShape(.circle).disabled(
            !runtime.canSend || model.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
          ).accessibilityLabel("Send message").accessibilityIdentifier("send-message")
        }
      }
      .padding(6).background(
        Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 27)
      )
      .overlay(RoundedRectangle(cornerRadius: 27).stroke(.primary.opacity(0.06), lineWidth: 1))
      .sendGeometry("composer")
      .padding(.horizontal, 14).padding(.top, 8).padding(.bottom, 10).frame(maxWidth: 860).frame(
        maxWidth: .infinity
      ).background(.bar)
    }
  }
  private struct TailPosition: PreferenceKey {
    static let defaultValue: CGFloat = .infinity
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
  }
  private struct ThinkingIndicator: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false
    var body: some View {
      HStack(spacing: 8) {
        Circle().fill(.secondary).frame(width: 8, height: 8).opacity(pulse ? 0.35 : 0.9)
        Text("Working…").font(.caption).foregroundStyle(.secondary)
      }.accessibilityLabel("Agent is responding")
        .onAppear {
          if !reduceMotion {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
              pulse = true
            }
          }
        }
    }
  }
  private struct MessageRow: View {
    let item: ChatItem
    var inFlight = false
    @State private var copied = false
    var body: some View {
      HStack(alignment: .top) {
        if item.role == "user" { Spacer(minLength: 32) }
        VStack(alignment: .leading, spacing: 8) {
          if ["tool", "thought", "approval"].contains(item.role) {
            DisclosureGroup {
              MarkdownView(source: item.text).padding(.top, 6)
            } label: {
              Label(
                item.title.isEmpty
                  ? (item.role == "thought" ? "Thinking" : "Tool activity") : item.title,
                systemImage: item.role == "thought"
                  ? "brain" : item.role == "approval" ? "checkmark.shield" : "terminal"
              )
              .font(.callout).foregroundStyle(.secondary)
            }
          } else if item.role == "user" {
            Text(item.text).textSelection(.enabled)
          } else {
            MarkdownView(source: item.text)
          }
          if !item.status.isEmpty { Text(item.status).font(.caption2).foregroundStyle(.secondary) }
          if item.role != "user" && !item.text.isEmpty {
            Button {
              UIPasteboard.general.string = item.text
              copied = true
            } label: {
              Label(
                copied ? "Copied" : "Copy",
                systemImage: copied ? "checkmark" : "doc.on.doc"
              ).font(.caption2).foregroundStyle(.tertiary)
            }.buttonStyle(.plain).accessibilityLabel("Copy message")
              .task(id: copied) {
                if copied {
                  try? await Task.sleep(for: .milliseconds(1800))
                  if !Task.isCancelled { copied = false }
                }
              }
          }
        }
        .padding(item.role == "user" ? 14 : 0)
        .background(
          item.role == "user" ? Color(uiColor: .secondarySystemBackground) : .clear,
          in: RoundedRectangle(cornerRadius: 20)
        )
        .sendGeometry(item.id)
        .opacity(inFlight ? 0 : 1)
        if item.role != "user" { Spacer(minLength: 0) }
      }.frame(maxWidth: .infinity, alignment: item.role == "user" ? .trailing : .leading)
    }
  }
#endif
