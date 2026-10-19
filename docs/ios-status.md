# iOS native client

Started 2026-10-14 at the user's request to align the iOS experience with Android.
This replaces the deferred iOS shell. Minimum iOS 17; iPhone and iPad, SwiftUI.
The shared Swift package also builds on macOS for protocol tests; there is no
macOS application in this delivery.

## Implemented

| Area | iOS behavior |
| --- | --- |
| Account | System-browser AuthKit PKCE, strict single-use state callback, serialized rotating refresh, assigned-region bootstrap/selection, remote logout |
| Storage | Keychain, non-synchronizing and `WhenUnlockedThisDeviceOnly`; access tokens only in memory; account-scoped encryption keys |
| Home | Sessions/Projects switch, search, paginated sessions, project filter, pull to refresh, device status, adaptive iPad list/detail |
| Creation | Projects, project targets, online device/agent selection, absolute working directory, one manager session ID per form, explicit status check after uncertain creation |
| Conversations | Immediate local echo, direct POST-stream chunks, 50 ms publication, lazy history, older pages, user-controlled tail following, stop, reconnect, scene background/foreground recovery |
| Content | Headings, inline emphasis/links, lists and task lists, tables, selectable code fences, copy actions, folded tool/thought output |
| Approvals | Server-supplied decision options, duplicate-tap guard, ordinary-session resume, explicit uncertain outcome |
| Encryption | CryptoKit AES-GCM/HKDF, P-256 legacy-v1 host authorization, fixed-boundary replay, authenticated cursor advancement, encrypted prompt/cancel, separate native/manager IDs |
| Presentation | System light/dark appearance and text sizing, safe-area keyboard composer, breathing response indicator with Reduce Motion support, NASA Earth welcome, existing Pax app icon |

No credentials, live account data, server secrets, or production encryption keys
are included. Test keys and account IDs are synthetic. Account login and E2EE
key authorization remain distinct. Unknown transports and missing keys fail
closed; there is no plaintext fallback.

## Boundaries

`WorkspaceShell` / SwiftUI -> `WorkspaceModel` -> `WorkspaceRepository` ->
`UserAuth` and `PaxAPI`. `SessionRuntime` owns history, replay, live streams,
command correlation and timeline normalization. `SecureVault` owns Keychain
persistence. `EnvelopeCrypto` calls platform cryptography only.

Sending updates the visible timeline before starting the network task. A plain
send cancels and fences the durable observer, consumes the POST's live chunks,
then reconciles history before restarting observation. A missing terminal event
leaves delivery unconfirmed and disables another send until reconnection.
Neither ordinary POSTs nor encrypted commands are automatically resent.

Foreground connections are paused in the background; returning reloads history
before resuming. Only the selected session is actively observed. Drafts survive
session switching in the same process, not process death. There is no offline
outbox or background-execution promise.

## Verified source contracts

Read against sibling `pax-manager-android-native` base `a3d01e4` and
`pax-console-workos` base `630bf29`, plus the existing local Android implementation.
No gateway or backend files were changed for this work.

- Native authentication and callback: `workers/region-directory/src/native-client.ts`.
- Session/project/target fields: `api/pax_manager.thrift`.
- Create, read, and approvals: `internal/manager/userapi/service.go`, `project.go`.
- Ordinary conversation and observer: `internal/manager/conversation.go`, `session_observer.go`.
- Encrypted replay: `internal/manager/e2ee_replay.go`.
- Envelope and key packages: Console `src/features/e2ee/envelope.ts`, `pairing.ts`.

The currently deployed callback recognizes `native.android.<random>` states.
iOS intentionally uses that existing wire prefix with its own random state and
PKCE verifier. It is not an Android account or device identifier. The fixed
`net.paxtech.workspace://auth/callback` scheme is registered in the iOS app.
Changing the prefix requires a separately tested gateway change; no deployment
is needed for the compatible prefix.

Apple references: [system browser authentication](https://developer.apple.com/documentation/authenticationservices/authenticating-a-user-through-a-web-service),
[CryptoKit](https://developer.apple.com/documentation/cryptokit).

## Build and verify

```sh
swift test --package-path apple/Packages/PaxWorkspaceKit
xcodebuild -project apple/PaxWorkspace.xcodeproj -scheme PaxWorkspace \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/pax-clients-ios CODE_SIGNING_ALLOWED=NO build

# Use ad-hoc simulator signing for RUNNING the app or UI tests: unsigned
# simulator apps can compile but cannot access Keychain on the current runtime.
xcodebuild -project apple/PaxWorkspace.xcodeproj -scheme PaxWorkspace \
  -destination 'platform=iOS Simulator,id=<isolated-simulator>,arch=arm64' \
  -derivedDataPath /tmp/pax-clients-ios \
  CODE_SIGN_IDENTITY=- CODE_SIGNING_ALLOWED=YES ONLY_ACTIVE_ARCH=YES test

PAX_IOS_INTEROP_OUTPUT=/tmp/pax-ios-envelope.json \
  swift test --package-path apple/Packages/PaxWorkspaceKit \
  --filter nativeCommandRoundTripAndRandomNonces
node --experimental-strip-types scripts/verify-ios-web-interop.mjs
```

The three protocol/runtime suites use Swift Testing and synthetic fixtures.
UI tests exercise an in-process synthetic transport through the real repository,
auth refresh, runtime and views. The preview harness is compiled only for DEBUG
simulator builds, never Release or physical devices. It normally uses an
in-memory vault. The Keychain restart test opts into a
separate `net.paxtech.workspace.uitests.synthetic` service; its second launch is
read-only at setup and must restore the previously stored rotating token. No test
contacts the network. The harness shows `PREVIEW / SYNTHETIC DATA` in the UI.
Normal launch uses Keychain and the production native gateway.

Artifacts and screenshots are under ignored `artifacts/ios-initial/`.
The initial UI attempts exposed ambiguous test selectors and a preview badge
covering navigation controls; the badge and selectors were corrected. The
unsigned launch also exposed the simulator Keychain signing requirement above.

## Verification on 2026-10-15

- Xcode 27 / iOS 27 simulator, minimum deployment target iOS 17.
- 18 Swift protocol/runtime tests passed. These cover strict PKCE callbacks,
  concurrent refresh, history normalization, 5,000 chunks, interrupted mutation
  outcomes, cancellation fences, encrypted replay pagination/snapshot changes,
  authenticated cursors, route tampering and distinct native session identity.
- Existing WebCrypto vectors decrypt and unwrap with CryptoKit. The existing
  Console WebCrypto implementation also decrypted a fresh iOS command.
- Five UI tests passed on isolated iPhone 18 Pro and five on iPad Pro 13-inch M5
  in dark mode: welcome, send/approval/encrypted boundary, project/session
  creation, maximum accessibility text and keyboard, and actual Keychain
  save/restart/read with synthetic tokens. The final composer sizing adjustment
  additionally rechecks ordinary and accessibility-size messaging on iPhone.
- Generic iOS simulator build and unsigned physical-device Release build passed.
  The Release build is a compilation check, not a signed distributable app.
  Final Swift formatting/lint and `git diff --check` passed.
- Screenshots were inspected for welcome, list/detail, conversation/keyboard,
  creation and maximum text size. UI fixture checks are not production access.
- iPhone final suites: `iphone-layout-final.xcresult`, `iphone-composer-final.xcresult`.
  iPad final suite: `ipad-dark-final.xcresult`. Named PNGs and logs are saved next
  to those bundles in `artifacts/ios-initial/`.

## Initial acceptance gaps (before the follow-up below)

- Real WorkOS sign-in/cancel/restore/logout on iPhone, including region routing.
- Real agent send/stop/approval and encrypted host authorization across network
  changes, revoked sessions, extended backgrounding and long histories.
- Physical-device accessibility, performance, battery and keyboard verification.
- The initial build lacked composer flight, the full orbital scene, entry camera
  motion and syntax coloring. These are implemented in the follow-up below.
- The initial Markdown subset was replaced with the GFM parser in the follow-up.
  Attachments, push, localization and offline commands remain outside this version.
- Initial builds were unsigned for physical devices; a development-signed build
  is now available (follow-up below). Simulator tests are not live-account or
  physical-device acceptance; TestFlight/App Store submission is not complete.

## Android parity follow-up — 2026-10-15

Compared with the current Android checkout, including its uncommitted creation,
rich-content, activity grouping and Moon/starfield work. Android source was not
changed by this follow-up.

| Area | Follow-up behavior |
| --- | --- |
| Welcome | Original Pax mark, density-scaled warm/cool starfield, same day/cloud/night NASA textures, shared J2000 orbit elements/time scale, Earth rotation, tidally facing Moon, three inclined spacecraft orbits, seven distant planets plus Sun and Saturn rings |
| Entry | Signed-in welcome and camera approach/dissolve into workspace; animation pauses when inactive and respects Reduce Motion |
| Sending | Immediate dispatch/local echo retained; a 420 ms TextKit glyph-fragment flight follows the measured composer and bubble; matches canonical turn/user/text, follows a moving landing row, settles on resize/disappearance, skips code/bidi/overflow/accessibility text/reduced motion |
| Activity | Adjacent tool/reasoning/permission steps grouped only within the same turn, status summary, nested disclosures with bounded selectable details; answers stay separate and pending approvals remain available through Approvals |
| Markdown | Pinned Swift Markdown 0.7.3 / cmark-gfm parser, nested lists/quotes, reference links, task boxes, tables with alignment, unfinished/indented code, literal HTML, explicit HTTPS image loading, safe link schemes, copy feedback and light/dark code colors |
| Limits | Same 150 KB rich-content and 20 KB code-coloring bounds as Android; plain selectable fallback, wrapping for exceptionally long code lines, bounded nesting |
| Details | Canonical agent display names and device names, session-details sheet, explicit approval confirmation |
| Recovery | Cold-start URL callback queued until restore completes, duplicate callback consumed once, late old-account refresh fenced from a new login |
| Home | Polling retains loaded session pages, coalesces overlapping loads, deduplicates IDs and pauses while inactive |

The orbital scene uses native SceneKit geometry/materials; it is not a pixel copy
of Android's Canvas/AGSL renderer. Terrain/craters and spacecraft meshes are
illustrative, and the scene is not a live astronomical ephemeris. Code coloring
covers common C-family, Swift, Kotlin, Python, shell, YAML, TOML and SQL tokens;
unknown languages retain plain code. Mathematical notation and HTML remain
literal, as on Android. No remote images load until the user requests them.

The parser dependency is the [Swift project's GFM parser](https://github.com/swiftlang/swift-markdown).
Scene materials use Apple's documented
[Metal shader-modifier arguments](https://developer.apple.com/documentation/scenekit/scnshadable).
The first GPU validation run caught a grayscale texture format unsupported by
Simulator. Textures now decode through an explicit sRGB RGBA8 context at a
maximum 2048-pixel dimension, with Metal validation left enabled. Shader and
texture failures were fixed, followed by successful welcome/entry tests.

Follow-up artifacts are under ignored `artifacts/ios-parity/`:

- 26 Swift tests across six suites pass, including new GFM structure, link
  policy, lexical boundaries, motion fallback, grouping, orbit continuity,
  paging, duplicate/cold callback and old-refresh/new-login race tests.
- Seven UI tests on iPhone and seven on iPad in dark appearance pass
  (`iphone-final.xcresult`, `ipad-final.xcresult`), including the original
  streaming/encryption/creation/large-text/Keychain cases plus signed-in entry,
  session details, wrapped-text/code/bidi sending. Small final motion changes
  have an additional targeted run (`motion-final.xcresult`).
- The current Android unit-test baseline has 60 tests, zero failures/errors;
  Gradle reused the existing successful task outputs.
- A development-signed Release build was produced with the local Apple team.
  Its profile, included device eligibility and package hashes are checked locally.
  No certificate, private key or provisioning profile is added to source control.
- Final screenshots and send-motion contact sheets were inspected. The iPad
  large-text test rotates to landscape; screenshots taken during rotation do not
  substitute for a sustained physical-device orientation/performance test.

A previously paired physical iPhone was found with Developer Mode enabled, but
CoreDevice could not establish a connection. A signed build does not establish
successful installation or real account/network/E2EE acceptance. Real WorkOS,
real agent send/stop/approval, encrypted host authorization and physical-device
performance remain acceptance work. TestFlight/App Store submission has not
been performed. Attachments, push, localization and offline commands are also
absent from the current Android baseline and remain outside this parity pass.

Local development package:
`artifacts/ios-parity/PaxWorkspace-ios-0.1.0-development.ipa` (3,918,158 bytes).
SHA-256: `cd8e7f3c89d7849cc001387b2594026669a7a9ed856a6803c489a0ef30f8302a`.
The development profile includes the paired iPhone and expires 2027-09-19.
Signature verification passed; Release binary inspection confirmed that preview
flags, synthetic account strings and test vectors are absent. Resource
acknowledgements include the NASA sources and Swift Markdown/cmark licenses.
The final settings/Keychain restart check also passed (`settings-final.xcresult`).
This IPA is a development install artifact for provisioned devices, not a
TestFlight or App Store build.
