public enum WorkspaceSection: String, CaseIterable, Identifiable, Sendable {
    case sessions, projects, devices, settings

    public var id: Self { self }
}
