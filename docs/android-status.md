# Android internal client

Date: 2026-10-13. See [UI and login integration](android-ui-login-fix.md) for
the subsequent visual redesign and production gateway fix. This supersedes the initial Android-only limitations in
`verification.md`; that document records the earlier preparation stage.

## Implemented

- Native Kotlin/Compose UI, Console-aligned dark theme, Hilt dependency injection,
  ViewModel/StateFlow state, and Navigation Compose.
- Existing `client_commit_v1` device login: regional candidates, external browser
  confirmation, pinned account identity, commit, secure save, acknowledgement,
  cancellation, restoration, and API-key revocation on sign-out.
- Keystore AES-GCM protection for an atomic, private, no-backup credential/key
  store. Login credentials and E2EE access remain separate.
- Paginated session list, project filtering, device status, and approval review.
- Ordinary session history, conversation POST, GET event observation, stop request,
  and approval decision followed by ordinary-session resume when route metadata
  is available.
- Encrypted replay with a fixed snapshot boundary, authenticated cursor advancement,
  SSE reconnect, encrypted prompt/cancel commands, and strict route/epoch checks.
- Existing legacy-v1 P-256 device authorization using a high-entropy secret and a
  command run on the agent host. This is a supported upstream protocol, separate
  from the web client's newer eight-digit OPAQUE flow.
- Lazy conversation rows, stable IDs, 50 ms publication of accumulated output,
  background parsing/decryption, earlier-history loading, code fences, selectable
  text, and collapsed long/tool/reasoning output.
- User-controlled reconnect; mutations are not automatically resent after an
  ambiguous network outcome.

## Validation

See generated reports under `android/app/build/reports/` and the final verification
record appended below. Protocol tests use synthetic upstream WebCrypto vectors;
emulator interaction tests use MockWebServer and synthetic credentials.

The isolated toolchain is in `.local-tools/` (ignored): Temurin JDK 17, SDK 36,
build-tools 35, platform tools, and an API 35 ARM64 AOSP emulator. The Google APIs
image failed archive validation; the smaller official AOSP image installed and
booted successfully. No global shell configuration was changed.

## Initial production findings (superseded by the linked gateway fix)

- Current machine requests to both regional `/api/v1/user/self/me` endpoints
  returned Cloudflare 403 / error 1010. Do not infer that a real phone is blocked
  identically; do not claim native production login or live messaging passed.
- A real browser confirmation, Android credential receipt, region routing, logout,
  and live agent prompt/stop/approval flow require verification together.
- E2EE primitive interoperability is tested; complete pairing and streaming against
  a live paxd have not been observed.
- The user chose to retain the project locally. No remote repository creation
  or publication is requested at this stage. The intended repository remains
  `pax-beehive/pax-clients`.

## Remaining product work

This build focuses on continuing existing sessions. New-session creation, eight-digit
OPAQUE pairing, attachments, complete Markdown rendering, localization, push
notifications, and store distribution are not implemented. Legacy encrypted history
without indexed turn references may omit historical prompts; there is no plaintext
fallback. Only one session is actively observed at a time.

The 5,000-chunk test proves message preservation, not a real-device frame-rate or
battery benchmark. Midrange-device performance, very large expanded messages,
network handover, extended backgrounding, and low-memory process death still need
physical-device validation. No release signing or store submission was performed.

## Next integration sequence

1. Completed: production gateway, real account login, ordinary history, live
   Claude prompt/reply, test-session stop and process-restart credential recovery.
2. Remaining: live approval, extended reconnect, logout/revocation, device
   authorization, encrypted history and encrypted prompt.
3. Fix discrepancies found by those checks before calling the client production-ready.
4. Extend creation, rich content, notifications, and physical-device coverage.

## Initial 0.1.0 local verification

- `assembleDebug`, `testDebugUnitTest`, `lintDebug`, and
  `connectedDebugAndroidTest`: passed. Unit tests: 13; emulator tests: 2;
  no failures, errors, or skips.
- Rebuilt and re-ran lint after the final manifest-only backup exclusion change.
  Lint report: 0 errors, 18 warnings (17 dependency-update notices and one
  backup compatibility advisory); no lint checks disabled. Backups are disabled
  explicitly and credentials are stored in the no-backup directory.
- Independently decrypted the freshly emitted Android envelope with the existing
  `pax-console-workos` WebCrypto implementation: passed.
- Viewed the emulator conversation screenshot with synthetic test data; composer
  remains above the keyboard. This is not a live production conversation.
- APK: `artifacts/paxworkspace-android-0.1.0-debug.apk` (14,430,502 bytes), debug signed.
- SHA-256: `d55cea44bb297047ce8ec46d7c0ff8f678d1dbddf9091696a80568fb44c0a8c1`.
- Logs, screenshot, and lint report copied to ignored `artifacts/`. Local SDK,
  caches, signing material, and test outputs are excluded from source control.
