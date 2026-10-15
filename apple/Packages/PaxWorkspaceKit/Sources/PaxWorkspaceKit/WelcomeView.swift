#if os(iOS)
  import SwiftUI
  import SceneKit

  struct WelcomeView: View {
    var signedIn = false
    let loading: Bool
    let error: String?
    let signIn: () -> Void
    let retry: () -> Void
    @Environment(\.scenePhase) private var scenePhase
    @State private var departing = false
    @State private var dissolve = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
      GeometryReader { geometry in
        ZStack {
          Color(red: 0.025, green: 0.03, blue: 0.05).ignoresSafeArea()
          WelcomeStarfield(animated: !reduceMotion && scenePhase == .active)
            .ignoresSafeArea().accessibilityHidden(true)
          ScrollView {
            VStack(spacing: 0) {
              HStack(spacing: 9) {
                PaxMark().fill(.white).frame(width: 30, height: 30)
                Text("PaxWorkspace").fontWeight(.semibold)
              }.font(.headline).padding(.top, 24)
              WelcomeScene(animated: !reduceMotion && scenePhase == .active, departing: departing)
                .frame(
                  height: max(180, geometry.size.height * 0.43)
                )
                .scaleEffect(departing && !reduceMotion ? 4 : 1)
                .animation(.easeInOut(duration: 1.05), value: departing)
                .zIndex(departing ? 1 : 0)
                .accessibilityHidden(true)
              VStack(spacing: 16) {
                Text("Your workspace.\nWherever you are.").font(
                  .system(.largeTitle, design: .rounded, weight: .semibold)
                ).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                Text("Stay close to your agents.\nKeep your work moving.").font(.body)
                  .foregroundStyle(.white.opacity(0.6)).multilineTextAlignment(.center)
              }
              Spacer(minLength: 20)
              VStack(spacing: 14) {
                if let error {
                  Text(error).font(.footnote).foregroundStyle(.orange).multilineTextAlignment(
                    .center)
                  Button("Retry saved sign-in", action: retry).font(.footnote)
                }
                Button(action: enter) {
                  HStack(spacing: 10) {
                    if loading { ProgressView().tint(.black) }
                    Text(
                      loading
                        ? "Connecting…" : signedIn ? "Enter workspace" : "Continue to workspace"
                    ).fontWeight(.semibold)
                    if !loading { Image(systemName: "arrow.right") }
                  }
                  .frame(maxWidth: .infinity).padding(.vertical, 17)
                }.buttonStyle(.plain).foregroundStyle(.black).background(.white, in: Capsule())
                  .disabled(loading || departing).accessibilityIdentifier("sign-in")
                Text("Secure sign-in · Your devices, your work").font(.caption).foregroundStyle(
                  .white.opacity(0.4))
              }.frame(maxWidth: 420).padding(.horizontal, 28).padding(.bottom, 20)
            }.frame(maxWidth: .infinity).frame(minHeight: geometry.size.height)
          }.scrollIndicators(.hidden)
          Color(uiColor: .systemBackground).opacity(dissolve ? 1 : 0).ignoresSafeArea()
            .allowsHitTesting(false)
        }.foregroundStyle(.white)
          .task(id: "\(departing)-\(scenePhase)") {
            guard departing, scenePhase == .active else { return }
            if reduceMotion {
              signIn()
              return
            }
            try? await Task.sleep(for: .milliseconds(700))
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.35)) { dissolve = true }
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            signIn()
          }
      }
    }
    private func enter() {
      if signedIn { departing = true } else { signIn() }
    }
  }
#endif
