# Protocol provenance

This directory is the cross-platform contract boundary. No speculative API
schema is checked in. Backend source remains authoritative.

Android project creation uses `POST /api/v1/user/self/projects` with
`display_name` and reads `data.project`. Verified against
`pax-manager-android-native/api/pax_manager.thrift` and
`internal/manager/userapi/project.go` (100 UTF-8-byte name limit, server-assigned
ID). The native gateway adds only that exact POST to its authenticated allowlist.

## Source map

Paths below are relative to sibling checkouts in the current Pax workspace.
Console revision: `630bf29`; Manager revision: `6b0f760`.

| Concern | Source |
| --- | --- |
| HTTP schema | `pax-manager-workos/api/pax_manager.thrift` |
| Generated OpenAPI | `pax-manager-workos/internal/manager/openapi_generated.json` |
| Browser auth | `pax-manager-workos/workers/region-directory/src/workos-auth.ts` |
| Identity and region | `pax-manager-workos/workers/region-directory/src/index.ts`, `workos-identity.ts` |
| REST paths and response handling | `pax-console-workos/src/features/api/client.ts`, `resources.ts`, `types.ts` |
| Encryption envelope | `pax-console-workos/src/features/e2ee/envelope.ts`, `envelope.test.ts` |
| Key authorization | `pax-console-workos/src/features/e2ee/short-code-crypto.ts`, `pairing.ts`, `key-distribution.ts` |
| History and live transport | `pax-console-workos/src/features/e2ee/replay.ts`, `transport.ts`, `use-e2ee-session-runtime.ts` |

## Verified browser route examples

These are existing route semantics, not proof that native credentials work:

- `POST /api/v1/region/bootstrap`: account/region bootstrap on the gateway.
- `GET /api/v1/user/:user_id/projects`: project collection.
- `GET /api/v1/user/:user_id/nodes`: connected device collection.
- `GET /api/v1/user/:user_id/sessions`: paginated session collection.
- `GET /api/v1/user/:user_id/approvals`: pending approvals.
- `POST /api/v1/user/:user_id/approvals/:approval_id/decision`: decision.

Browser resource calls prepend `/api/pax`; the gateway unwraps that prefix.
Manager resource responses use a `code` / `data` / `message` envelope. Gateway
authentication responses use a different shape; do not apply one universal
decoder. Confirm the final native origin and path prefix during auth integration.

## Fixture policy

Before implementing transport, add small synthetic examples of actual response
shapes with the exact source revision and extraction method. Test both clients
against the same fixtures. Cover unknown enum values, omitted/null optional
fields, pagination, authorization errors, and non-JSON gateway failures.

Encryption fixtures must exercise web-to-native and native-to-web decoding,
authenticated metadata, wrong direction/key/epoch, malformed input, replay and
cursor boundaries, and tamper rejection. Use synthetic keys only; fixed nonces
are permitted in deterministic test vectors only. Never copy user histories,
tokens, credentials, or production key material into this repository.

## Android user login (0.1.2)

The client is a user-facing peer of Web, not a registered node. Authoritative
sources are `pax-manager-android-native/workers/region-directory/src/native-client.ts`
and the official WorkOS public-client PKCE protocol:
https://workos.com/docs/sdks/android and https://github.com/workos/workos-android.

- AuthKit authorization uses `provider=authkit`, S256 PKCE and a random state.
- The registered HTTPS redirect is `https://paxworkspace.net/auth/callback`.
  Native-prefixed states relay only code/state or error/state to the fixed
  `net.paxtech.workspace://auth/callback` URI. Web cookie login is unchanged.
- Code and refresh grants go directly to WorkOS `/user_management/authenticate`
  with the public client ID. No client secret is included in the APK.
- Access tokens remain in memory. The rotating refresh token, pending verifier
  and account metadata use Android Keystore-backed encrypted storage.
- `POST /api/v1/native/client/bootstrap` resolves the verified WorkOS subject to
  the existing user/region. Only an unassigned user chooses a region. No node or
  user API key is issued.
- REST/SSE `/api/v1/user/self/...` paths use the base
  `https://paxworkspace.net/api/v1/native/client/` with the first `/api/v1/` stripped.
  The gateway verifies WorkOS JWT signatures and forwards the existing signed
  user assertion and server-only Access credentials to the assigned Manager.
- `POST /api/v1/native/client/logout` revokes the verified WorkOS session.
- Old native `/{us|hk}/paxl/device-login/*` paths return 410. The old key route
  remains only for legacy compatibility and the upgrade's own-key revocation.

0.1.1 reused paxl device login, which created an offline node; that was an
incorrect domain model. Its commit/ack flow is not the current client contract.
E2EE key approval remains distinct from account login and node enrollment.

Synthetic WebCrypto fixtures: `android/app/src/test/resources/web-vectors.json`.
The fixture includes deliberately public test keys, never account data. Tests
check envelope decryption and P-256 root-key unwrapping. Android-generated
ciphertext is separately checked by `scripts/verify-web-interop.mjs` using the
original web implementation.

## Android session creation

Conversation identity uses the existing `AgentSession.agent_type` and matching
`Node.name` / `Node.hostname` fields from `api/pax_manager.thrift` in the Manager
checkout. See [conversation identity](../docs/android-conversation-identity.md).

The local Android creation flow uses the existing node-scoped session POST,
node Agent GET and project target GET. Request fields and provenance are recorded
in [mobile session creation](../docs/android-new-session.md). The native gateway
allowlist additions passed validation and were deployed after explicit user approval;
see the linked document for the active version and live checks.

## iOS native client

iOS uses the same public-client PKCE, native gateway, manager routes, SSE and
legacy-v1 E2EE wire contracts as Android. The fixed custom callback scheme is
registered in `apple/App/Info.plist`; the deployed legacy `native.android.` state
prefix is retained for compatibility, with fresh per-attempt state/verifier.
See [iOS source map and validation](../docs/ios-status.md). Swift tests consume a
copy of the same synthetic WebCrypto fixture and independently emit a CryptoKit
command for `scripts/verify-ios-web-interop.mjs` to decrypt with the web source.
