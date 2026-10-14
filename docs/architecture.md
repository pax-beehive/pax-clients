# Architecture

## Platform choices

- iOS: Swift, SwiftUI, structured concurrency; native networking and secure
  storage when integration begins. Swift packages are the reuse boundary for
  future macOS support.
- Android: Kotlin, Jetpack Compose, coroutines, platform lifecycle and secure
  storage. Start with a single app module; extract deep modules when real feature
  boundaries exist.
- Across platforms: share wire contracts, synthetic fixtures, error semantics,
  and cryptographic interoperability vectors. UI and lifecycle remain native.

References: [Apple SwiftUI](https://developer.apple.com/swiftui/),
[Android Compose](https://developer.android.com/compose),
[AGP 8.13 baseline](https://developer.android.com/build/releases/agp-8-13-0-release-notes),
[Compose BOM](https://developer.android.com/develop/ui/compose/bom).

## Boundaries as features are added

```text
Native view -> feature state / use case -> repository
  -> authenticated regional gateway transport
  -> encrypted session runtime where required

System-browser login -> native session credential store
Device authorization -> separate E2EE key store
```

UI must not construct ACP frames, decrypt envelopes, or refresh credentials.
Session runtime owns replay, live cursor continuity, normalization, and command
correlation. Logout clears account-scoped transient state; key deletion and
device revocation need explicit, consistent lifecycle rules.

## Current source evidence (2026-10-13)

Inspected local source revisions, not live production behavior:

- `pax-console-workos`: `630bf29`.
- `pax-manager-workos`: `6b0f760`.

The region-directory Worker implements `/auth/login`, `/auth/callback`,
`/auth/session`, and `/auth/logout`. It stores the PKCE verifier in an encrypted
browser transaction cookie and returns an encrypted HttpOnly session cookie.
The browser gateway authenticates that cookie before regional API routing.
That cookie flow remains separate from native user-key authorization.

## Android authentication choice after source inspection

The Android implementation reuses the existing user-device login protocol from
`paxl/internal/facade/auth_regional.go`, `client_commit_v1`. This is a user API
credential, not a paxd Node Key. The app creates regional login candidates, opens
the existing WorkOS-backed browser confirmation page, pins the confirmed user,
commits one candidate, saves the credential before acknowledgement, and cancels
the other candidate. Sign-out revokes this app's API-key ID.

Browser cookies remain in the browser. The native gateway at
`/api/v1/native/{us|hk}/` now explicitly supports the existing Manager user-key
contract. Access service credentials stay in the Worker, while Manager validates
the key and resource permissions. Start responses normalize browser confirmation
to the canonical WorkOS site. No WorkOS secret is stored in the app.

User API keys are revocable credentials; they do not have an implemented OAuth
refresh-token cycle. Production start/poll and authorization boundaries are
verified from Android, as are real-account confirmation and ordinary-session
Claude message round trips. Live encrypted-session verification remains open. See [current integration evidence](android-ui-login-fix.md).

## Encryption and streaming

The web envelope uses versioned AES-GCM with HKDF-SHA256, direction-specific key
derivation, route-bound authenticated data, and explicit key epochs. Reproduce
the exact byte encoding through fixtures; matching algorithm names is insufficient.

The current short-code-v2 pairing path uses OPAQUE via
`@serenity-kit/opaque@1.1.0`. A compatible native implementation or an explicitly
supported alternative key-transfer flow must be proven before shipping. This
repository does not implement OPAQUE. Android initially uses upstream legacy-v1
P-256 key wrapping with a high-entropy secret and local agent-host confirmation.

Initial encrypted replay freezes a head cursor. Live SSE starts after that
boundary, and reconnects retain only authenticated cursors. Manager session IDs
and ACP/native session IDs are distinct. Follow current replay and command
semantics rather than treating messages as a simple WebSocket chat stream.

On backgrounding, release unnecessary foreground connections. On return, restore
authentication, recover missed events, and then resume live output. Never promise
a permanently running mobile socket. Do not automatically resend a mutation
after a timeout unless the server's idempotency contract has been verified.

## macOS path

`PaxWorkspaceKit` currently has a platform-neutral navigation model and a SwiftUI
shell, buildable on macOS for package validation. This is not a macOS application.
Later, extract transport and session features into additional packages as they
appear, then add a macOS app target using those packages with desktop-specific
window and navigation behavior. iOS scene assumptions stay in `apple/App`.
