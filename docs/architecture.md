# Architecture

## Platform choices

- iOS: Swift, SwiftUI, structured concurrency; native networking and secure
  storage through URLSession, Keychain and CryptoKit. Swift packages are the reuse boundary for
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
That cookie flow remains separate from the native WorkOS bearer session.

## Android user authentication (0.1.2)

Android and Web use the same WorkOS identity and regional account directory.
Android uses public-client S256 PKCE and its own rotating user session; browser
cookies stay in the browser. The registered HTTPS callback relays only a code
and state to a fixed Android callback. Account login never registers a node.

UserAuth owns serialized refresh and Keystore persistence. The repository and
session runtime obtain current access tokens through it. The native user gateway
verifies WorkOS JWTs and attaches the same signed identity assertion used by Web.
Regional Access service credentials remain server-side. See
[user login and foldables](android-user-login-foldables.md) for exact verification.

The prior paxl device-login implementation was removed because its credential
issuance transaction also created an offline node. Old own-key revocation is
retained as an upgrade migration, not as a login option.

## Foldable presentation

WindowManager supplies separating hinge geometry; available window width selects
single-pane or list/detail presentation. Both panes retain their composition
identity across layout transitions. A configuration change preserves the active
ViewModel and does not restart the stream. Real backgrounding does suspend it.

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

`PaxWorkspaceKit` contains the shared auth, transport, crypto, repository and
session runtime, buildable on macOS for protocol tests. Its active UI is iOS-only. This is not a macOS application.
Later, extract transport and session features into additional packages as they
appear, then add a macOS app target using those packages with desktop-specific
window and navigation behavior. iOS scene assumptions stay in `apple/App`.

## iOS implementation

See [iOS status](ios-status.md) for the SwiftUI/Observation runtime, system-browser
PKCE compatibility, Keychain lifecycle and verified interoperability boundaries.
