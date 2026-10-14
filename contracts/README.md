# Protocol provenance

This directory is the cross-platform contract boundary. No speculative API
schema is checked in. Backend source remains authoritative.

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

## Android user-device login

Additional authoritative source inspected: `paxl/internal/facade/auth_regional.go`
and `auth.go`. Regional API origins are `https://api.paxworkspace.net` and
`https://hk-api.paxworkspace.net`.

- `POST /api/v1/paxl/device-login/start` with `protocol: client_commit_v1`.
- `POST /api/v1/paxl/device-login/poll` with login ID, poll token, action, and
  expected user ID. Actions: inspect, commit, ack, cancel.
- `GET /api/v1/user/self/me` identifies the user API credential owner.
- `DELETE /api/v1/user/self/api-keys/:key_id` revokes this app's credential.

Android reaches these contracts through the canonical native gateway:
`https://paxworkspace.net/api/v1/native/{us|hk}/`. It replaces the initial
`/api/v1/` prefix with this gateway base. The Worker forwards only allowlisted
routes to the selected region, using server-side Access credentials; Manager
still validates the user bearer credential. No Node Key is accepted as a user
credential. `/api/pax` remains the browser gateway prefix.

Gateway source: `pax-manager-android-native/workers/region-directory/src/native.ts`,
local commits `1de3456` and `59428fd`. Start responses use the canonical
`https://paxworkspace.net/paxl-login.html` confirmation page, including HK starts.
The Android client retains strict verification-host validation.

Synthetic WebCrypto fixtures: `android/app/src/test/resources/web-vectors.json`.
The fixture includes deliberately public test keys, never account data. Tests
check envelope decryption and P-256 root-key unwrapping. Android-generated
ciphertext is separately checked by `scripts/verify-web-interop.mjs` using the
original web implementation.
