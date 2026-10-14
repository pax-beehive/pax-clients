# PaxWorkspace clients

- Android UI is Kotlin and Jetpack Compose. Apple UI is Swift and SwiftUI.
- Keep platform entry points thin. Share Apple features through Swift packages.
- Keep transport, authentication, secure storage, encryption, and presentation
  separate as they are introduced. Do not create empty modules in anticipation.
- `contracts/README.md` records upstream sources. Confirm wire contracts against
  backend source before adding API clients. Do not invent mobile endpoints.
- Browser cookie login does not establish a native token contract.
- Never ship WorkOS server secrets or gateway signing secrets in a client.
- Existing encrypted sessions must never silently fall back to plaintext.
- Keep manager session IDs distinct from ACP/native session IDs.
- Do not implement cryptographic primitives. Use reviewed platform libraries and
  cross-platform protocol vectors. Login and E2EE key access are separate states.
- Mark preview data and unavailable features explicitly. An empty authenticated
  list is different from a not-yet-connected application.
- Follow system text sizing, accessibility semantics, dark mode, and safe areas.
- Before handing off changes, build affected platforms and report unavailable
  toolchains honestly. See README for commands. Update docs when scope or
  integration contracts change.
- Do not commit signing keys, device tokens, account data, or local SDK paths.
