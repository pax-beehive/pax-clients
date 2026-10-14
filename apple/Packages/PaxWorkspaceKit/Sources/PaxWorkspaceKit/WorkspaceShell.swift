import SwiftUI

/// The initial navigation shell. It does not represent a connected account.
public struct WorkspaceShell: View {
    @State private var selection: WorkspaceSection = .sessions

    public init() {}

    public var body: some View {
        TabView(selection: $selection) {
            section(.sessions, title: "Sessions", icon: "bubble.left.and.bubble.right") {
                ContentUnavailableView(
                    "Your agents, within reach",
                    systemImage: "bubble.left.and.bubble.right",
                    description: Text("Follow progress and continue your work from anywhere. Account connection is coming soon.")
                )
            }
            section(.projects, title: "Projects", icon: "folder") {
                ContentUnavailableView(
                    "One place for your projects",
                    systemImage: "folder",
                    description: Text("Your connected projects will appear here when account connection is available.")
                )
            }
            section(.devices, title: "Devices", icon: "desktopcomputer") {
                ContentUnavailableView(
                    "Work runs on your devices",
                    systemImage: "desktopcomputer",
                    description: Text("View the computers and servers running your agents once your account is connected.")
                )
            }
            section(.settings, title: "Settings", icon: "gearshape") {
                Form {
                    Section("Account") {
                        LabeledContent("Status", value: "Not connected")
                        Text("Account connection is coming soon.")
                            .foregroundStyle(.secondary)
                    }
                    Section("About") {
                        LabeledContent("App", value: "PaxWorkspace")
                        LabeledContent("Release", value: "Development preview")
                    }
                }
            }
        }
    }

    private func section<Content: View>(
        _ section: WorkspaceSection,
        title: LocalizedStringKey,
        icon: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        NavigationStack {
            content().navigationTitle(title)
        }
        .tabItem { Label(title, systemImage: icon) }
        .tag(section)
    }
}

#Preview {
    WorkspaceShell()
}
