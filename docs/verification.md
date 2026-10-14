# Preparation verification

Date: 2026-10-13.

## Passed locally

- iOS simulator build with Xcode 27.0, Swift 6.4:
  `xcodebuild -project apple/PaxWorkspace.xcodeproj -scheme PaxWorkspace -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/pax-clients-ios CODE_SIGNING_ALLOWED=NO build`.
- Shared package compiled on macOS:
  `swift build --package-path apple/Packages/PaxWorkspaceKit --scratch-path /tmp/pax-clients-swift`.
- Xcode project syntax: `plutil -lint apple/PaxWorkspace.xcodeproj/project.pbxproj`.
- Gradle launcher shell syntax: `bash -n android/gradlew`.
- Android XML resources and manifest parse successfully.
- Official Maven repositories returned HTTP 200 for pinned AGP 8.13.0,
  Kotlin Gradle plugin 2.2.10, Compose BOM 2025.09.01, and Activity Compose 1.10.1.
- Official Gradle 8.13 wrapper JAR checksum verified:
  `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.
- Gradle distribution checksum pinned in wrapper properties:
  `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.

Initial restricted-sandbox Apple builds failed because the compiler caches and
CoreSimulator services were inaccessible. Re-running with access to those
services passed; no source workaround was required.

Local build logs: `/tmp/pax-clients-ios-build.log` and
`/tmp/pax-clients-swift-build.log`. These are temporary, not committed artifacts.

## Not yet verified

- Android compilation, lint, or execution: this machine has no Java runtime or
  Android SDK. Dependency availability and wrapper checks are not APK evidence.
- CI execution: workflow prepared but not published or run.
- Simulator interaction, physical-device behavior, accessibility, signing,
  store submission, or distribution.
- Login, live API access, encryption, streaming, approvals, and notifications:
  these are planned features, not implemented or tested by the shells.
- macOS application behavior: only the shared package compiles on macOS.

## Repository state

Initialized a standalone local repository under `pax-clients`, branch `main`,
with origin `https://github.com/pax-beehive/pax-clients.git`. No commit or push
was made during preparation.

The authenticated GitHub CLI account `qiz029` could resolve the `pax-beehive`
organization, but `GET /repos/pax-beehive/pax-clients` returned HTTP 404. This
does not distinguish a missing repository from a private repository without
access. Remote creation/publication remains unverified.
