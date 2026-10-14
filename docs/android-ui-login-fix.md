# Android UI and native gateway integration

Historical 0.1.1 record. The device-login contract and test commands below were
superseded by [0.1.2 user login and foldables](android-user-login-foldables.md).
Do not use old fixture commands on a real-account emulator.

## UI

Android now uses the existing Console's neutral palette (canvas #262626,
surfaces #2d2d2d/#353535, ink #e7e7e7, hairlines #434343), small radii and compact
resource rows. Main navigation is Home, Approvals and Settings; Projects and
Devices are secondary views. Agent text is rendered directly in the timeline;
user messages retain a subtle background. Mobile body text remains 16sp and
controls retain touch targets. Login content has a maximum width and scrolls in
short windows. The system bars and launch background use the dark theme.

## Failure and fix

An actual Android network probe found HTTP 302 from both regional origins to
Cloudflare Access login, before reaching Manager. The earlier host-side
403/error 1010 observation was a separate path and did not establish the phone's
failure. The old client also discarded the regional exceptions behind a generic
connection message.

The client now calls the explicit canonical gateway bases:

- https://paxworkspace.net/api/v1/native/us/
- https://paxworkspace.net/api/v1/native/hk/

Transport maps existing `/api/v1/...` paths beneath those bases. Manager wire
contracts, browser confirmation, identity pinning and commit/save/ack remain the
same. Gateway service credentials stay server-side. Unknown routes, Node Keys,
user-supplied identities and browser cookies do not authorize native requests.
The client reports regional failures and never follows API redirects.

The companion gateway source is in the isolated sibling worktree
`pax-manager-android-native`, branch `codex/android-native-gateway`. Publication
was explicitly approved separately from Git hosting. Neither repository is
pushed. See that repository's `docs/handoff_20261013_200000.md` for deployment
and rollback details.

## Reproducible checks

Ordinary regression (no live account or API required):

```sh
ANDROID_SERIAL=emulator-5580 ./scripts/android.sh \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.paxtech.workspace.WorkspaceFlowTest
```

Opt-in production connectivity check, using temporary login requests that are
cancelled without issuing an account credential or touching the application's
vault:

```sh
ANDROID_SERIAL=emulator-5580 ./scripts/android.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.paxtech.workspace.LiveGatewaySmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.paxLiveSmoke=true
```

The check requires anonymous reads to return 401 and invalid-key reads to return
401 or 404. The current PostgreSQL Manager maps an unknown API key to 404; this
is distinct from a missing native endpoint. The live smoke is skipped unless
explicitly enabled. The production smoke does not test browser confirmation or credential issuance;
see the real-account verification below for the subsequent manual checks.

## Pixel / foldable debugging

Enable Developer options by tapping Build number seven times under About phone,
then enable USB debugging. Connect a data-capable USB cable to the Mac and approve
the computer's fingerprint on the unlocked phone. macOS needs no OEM USB driver.
The local SDK's `platform-tools/adb devices -l` should show the phone as `device`.
Select its serial explicitly when installing/debugging, because the emulator may
also be running. The APK supports API 26 and newer. Wireless debugging is another
option on Android 11 and newer, paired to this Mac on the same network.

Use real folded/unfolded transitions, rotation, keyboard input, font scaling,
background/foreground recovery and network handover to assess foldable support.
Emulated window dimensions do not prove hinge behavior or physical performance.

## Verified delivery (2026-10-13)

- Debug APK, 16 unit tests and Android lint passed.
- Two instrumented UI tests passed at 1080x2400 / 420dpi (about 411dp wide),
  and again at 1600x1800 / 380dpi (about 674dp wide). These exercise sign-in
  presentation, history, send, approval and Activity recreation with synthetic
  local data. Screenshots were inspected at both sizes; system keyboard and
  taskbar styling belong to the emulator OS.
- Opt-in production smoke passed: both regional starts and pending polls,
  anonymous and invalid-key denial, plus unchanged browser auth boundary.
- Companion Worker: 144 tests, typecheck and dry-run passed. Production version
  `e91fd9c2-53ca-40ec-b52e-4abe52ab526a` is active after explicit approval.
- Existing authenticated web Settings remained functional after deployment.
- The emulator was restored to its original dimensions; 0.1.1 was installed and
  launched. No physical Pixel was attached at verification time.

APK: `artifacts/paxworkspace-android-0.1.1-debug.apk`.
SHA-256: `7525519c1d7d10d0acb317e0eb2d031b3889012cc75001c54a41ceefa07337c6`.
Ignored local evidence: `artifacts/validation-0.1.1/` (build/lint/unit results,
production smoke XML, narrow/wide UI XML and screenshots).

Physical fold/unfold behavior and encrypted-session account-level validation
remain unverified. Subsequent real-account checks are recorded below. The AOSP emulator includes WebView shell,
not a full Chrome installation; if its browser cannot complete account sign-in,
use the login screen's copy-link option to confirm in a regular browser. This
still requires an explicit account confirmation before any credential is issued.

## Real-account continuation (2026-10-13)

After the user approved the browser confirmation, Android completed the
commit/save/ack sequence and opened the authenticated US workspace. Settings
showed the expected account, and Devices showed the Mac online. No account
credential was extracted or copied outside the application.

A dedicated text-only test session was created from the web using the ignored
`artifacts/live-agent-smoke` directory. Android discovered it, loaded its history,
and established a live connection. Stopping this test from Android changed the
web view to completed; a follow-up prompt sent from Android appeared in the same
web session. Neither prompt requested tools or file changes.

The local Codex adapter logged model metadata parsing errors for `max` and a
fallback-metadata warning for its selected model. Both Codex test requests
were stopped without a successful text response. The exact Codex runtime cause
remains unresolved; no global model configuration or harness was changed.
Account screenshots are retained only in ignored
`artifacts/validation-0.1.1/real-account/`.

The existing Claude connection provided the control test: its web-originated
prompt returned `PAX_ANDROID_CLAUDE_OK`, visible in Android history. A subsequent
prompt sent from Android returned `PAX_ANDROID_ROUNDTRIP`, visible in both clients.
Android's screenshot and UI hierarchy confirmed the response and Connected state.
This proves a live ordinary-session request/response path, not encrypted-session
pairing or every provider. Force-stopping and relaunching Android also restored
the authenticated workspace without another browser login.

Remaining live checks: encrypted pairing/replay/prompt, real approval decisions,
logout/revocation, physical Pixel networking and fold/unfold behavior. The Codex
runtime issue is separate from the now-verified native gateway and Claude path.
No application code or production deployment changed during this continuation.
