# PaxWorkspace native clients

Native Android and iOS clients for PaxWorkspace. **Android is the active priority**;
iOS remains an initial shell and macOS is a future target.

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
Version 0.1.11 refines the workspace, conversation, settings and empty states using
[reviewed Mobbin references](docs/android-visual-design.md).
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
apple/PaxWorkspace.xcodeproj  Initial iOS application
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

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Current version: 0.1.11.
Minimum OS: Android 8 / API 26. Application ID: `net.paxtech.workspace` (provisional).
A browser capable of opening HTTPS links is required for account sign-in.

Fixture UI tests reset their vault. Run them only on the dedicated integration
emulator, never on a personal device or an emulator with a real account.

Only debug builds permit cleartext traffic to localhost for emulator integration
tests. Release builds require HTTPS. Test credentials are synthetic and injected
by instrumentation into the normal secure storage; no production login bypass
or test account is embedded in the application.

## iOS (deferred)

Open `apple/PaxWorkspace.xcodeproj` and run the `PaxWorkspace` scheme on an iOS
simulator. Minimum iOS 17. Physical-device builds require your signing team.

```sh
xcodebuild -project apple/PaxWorkspace.xcodeproj -scheme PaxWorkspace \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/pax-clients-ios CODE_SIGNING_ALLOWED=NO build
swift build --package-path apple/Packages/PaxWorkspaceKit
```

## Design and contracts

- [MVP sequence](docs/mvp.md)
- [Architecture](docs/architecture.md)
- [Protocol source map](contracts/README.md)
- [Initial preparation verification](docs/verification.md)
- [Android delivery and validation](docs/android-status.md)
