# PaxWorkspace native clients

Native Android and iOS clients for PaxWorkspace. **Android is the active priority**;
iOS remains an initial shell and macOS is a future target.

Intended upstream: `https://github.com/pax-beehive/pax-clients`.

## Android status

An internal Android client implements system-browser device login, encrypted local
credentials, projects/devices/sessions, ordinary and encrypted session transport,
message sending, stop requests, approvals, history pagination, reconnect, and
local-device E2EE authorization. See [Android delivery status](docs/android-status.md)
for implemented behavior, validation, and remaining integration work.

This is not a store-ready release. Real-account login and authenticated resource
access and ordinary Claude session round trips have passed live verification;
encrypted-session and physical-device validation remain open. The native gateway
fix for regional Cloudflare Access redirects and current verification are recorded
in [UI and login integration](docs/android-ui-login-fix.md). At the user's request,
the project remains local; no remote repository has been created or pushed.

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
./gradlew :app:connectedDebugAndroidTest
```

On the prepared development machine, `./scripts/android.sh` selects the ignored
`.local-tools` JDK and SDK automatically. For example:

```sh
./scripts/android.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=emulator-5580 ./scripts/android.sh :app:connectedDebugAndroidTest
node --experimental-strip-types scripts/verify-web-interop.mjs
```

The last command uses the sibling `pax-console-workos` checkout, or a checkout
path supplied as the first argument, to independently decrypt Android's generated
synthetic test ciphertext using the existing web implementation.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Current version: 0.1.1.
Minimum OS: Android 8 / API 26. Application ID: `net.paxtech.workspace` (provisional).
A browser capable of opening HTTPS links is required for account sign-in.

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
