// swift-tools-version: 6.0
import PackageDescription

let package = Package(
  name: "PaxWorkspaceKit",
  platforms: [.iOS(.v17), .macOS(.v14)],
  products: [.library(name: "PaxWorkspaceKit", targets: ["PaxWorkspaceKit"])],
  dependencies: [.package(url: "https://github.com/swiftlang/swift-markdown.git", exact: "0.7.3")],
  targets: [
    .target(name: "PaxWorkspaceKit", dependencies: [.product(name: "Markdown", package: "swift-markdown")], resources: [.process("Resources")]),
    .testTarget(
      name: "PaxWorkspaceKitTests", dependencies: ["PaxWorkspaceKit"],
      resources: [.copy("Fixtures")]),
  ]
)
