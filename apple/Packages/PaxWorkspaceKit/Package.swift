// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PaxWorkspaceKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "PaxWorkspaceKit", targets: ["PaxWorkspaceKit"])],
    targets: [.target(name: "PaxWorkspaceKit")]
)
