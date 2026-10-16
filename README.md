# PaxWorkspace native clients

Native Android and iOS clients for PaxWorkspace. Android is the feature baseline;
iOS is now in active development with a native SwiftUI client. macOS is a future target.

Repository: [pax-beehive/pax-clients](https://github.com/pax-beehive/pax-clients).

[Download Android previews](https://github.com/pax-beehive/pax-clients/releases) ·
[Build checks](https://github.com/pax-beehive/pax-clients/actions/workflows/verify.yml) ·
[Android releases](docs/android-releases.md)

## Android status

An internal Android client implements system-browser WorkOS user login, encrypted local
credentials, projects/devices/sessions, ordinary and encrypted session transport,
message sending, stop requests, approvals, history pagination, reconnect, and
separate client E2EE key authorization. See [Android delivery status](docs/android-status.md)
for implemented behavior, validation, and remaining integration work.

The 0.1.2 client uses PKCE, rotating user sessions and adaptive foldable panes.
It no longer uses node/device login. See [user login and foldables](docs/android-user-login-foldables.md)
for current verification and migration details. Prior 0.1.1 live Claude checks
are historical and do not establish acceptance of the new authentication flow.
Version 0.1.13 adds a composer-to-message bubble transition and a breathing reply dot;
see [send interaction](docs/android-send-motion.md).
Version 0.1.12 adds immediate send feedback and renders live reply chunks directly;
see [conversation responsiveness](docs/android-conversation-responsiveness.md).
Version 0.1.11 refines the workspace, conversation, settings and empty states using
[reviewed Mobbin references](docs/android-visual-design.md).
The latest local refinement clarifies dual-pane actions and reduces list clutter;
see [foldable workspace refinement](docs/android-foldable-polish.md).
The local build also adds [mobile session creation](docs/android-new-session.md)
and restores the existing [Pax launcher logo](docs/android-brand-icon.md).
Home includes [Sessions / Projects switching and project creation](docs/android-projects.md).
The workspace uses [restrained color for actions and status](docs/android-color.md),
with [agent and device bylines](docs/android-conversation-identity.md) in conversations.
Agent replies now use [Markdown rendering](docs/android-markdown.md), including
tables, task lists, highlighted code, and a compact message-copy action.
Version 0.1.10 added real NASA Earth imagery and city lights on the night side.
See [imagery sources and rendering](docs/earth-imagery.md). The scene uses shared-time Keplerian
orbits and an Earth-following perspective camera. Orbital distances are in AU;
only disc sizes are enlarged for visibility. Satellites still orbit Earth,
and signed-in entry zooms toward its surface; see
[welcome and controls](docs/android-welcome.md).
Android APK prereleases are built and signed by GitHub Actions from version tags.
See [release and signing instructions](docs/android-releases.md).

## Layout

```text
android/                     Kotlin / Compose / Hilt Android application
apple/PaxWorkspace.xcodeproj  Native iPhone / iPad application
apple/Packages/               Shared Swift package for future Apple clients
contracts/                    Protocol provenance
scripts/                      Build and interoperability helpers
docs/                         Scope, architecture, validation, open work
```

## Build Android

Use JDK 17, Android SDK 36, and the checked-in Gradle wrapper:

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=<isolated-emulator> ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.paxFixtureTests=true
```

On the prepared development machine, `./scripts/android.sh` selects the ignored
`.local-tools` JDK and SDK automatically. For example:

```sh
./scripts/android.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=emulator-5584 ./scripts/android.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.paxFixtureTests=true
node --experimental-strip-types scripts/verify-web-interop.mjs
```

The last command uses the sibling `pax-console-workos` checkout, or a checkout
path supplied as the first argument, to independently decrypt Android's generated
synthetic test ciphertext using the existing web implementation.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Current version: 0.1.13.
Minimum OS: Android 8 / API 26. Application ID: `net.paxtech.workspace` (provisional).
A browser capable of opening HTTPS links is required for account sign-in.

Fixture UI tests reset their vault. Run them only on the dedicated integration
emulator, never on a personal device or an emulator with a real account.

Only debug builds permit cleartext traffic to localhost for emulator integration
tests. Release builds require HTTPS. Test credentials are synthetic and injected
by instrumentation into the normal secure storage; no production login bypass
or test account is embedded in the application.

## iOS development preview

Open `apple/PaxWorkspace.xcodeproj` and run the `PaxWorkspace` scheme on an iOS
simulator. Minimum iOS 17. Physical-device builds require your signing team.
See [iOS status and parity](docs/ios-status.md) for implemented features, protocol
validation, simulator signing, UI tests, and the remaining live-account checks.
The current iOS build includes project/session creation, plain and encrypted
streaming, approvals, GFM content with code colors, grouped activity, measured
send motion, and the Earth/Moon welcome scene. A local development-signed IPA
is available under ignored `artifacts/ios-parity/`; the paired iPhone must be
connected before physical-device acceptance can be completed.

```sh
xcodebuild -project apple/PaxWorkspace.xcodeproj -scheme PaxWorkspace \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/pax-clients-ios CODE_SIGNING_ALLOWED=NO build
swift test --package-path apple/Packages/PaxWorkspaceKit
```

## Design and contracts

- [MVP sequence](docs/mvp.md)
- [Architecture](docs/architecture.md)
- [Protocol source map](contracts/README.md)
- [Initial preparation verification](docs/verification.md)
- [Android delivery and validation](docs/android-status.md)
