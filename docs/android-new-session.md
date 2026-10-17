# Android session creation

## Behavior

Sessions has a top-right **New session** (+) action on compact and expanded
layouts. The modal form selects an optional project, project target, online
computer and Agent, an absolute working directory, and an optional name.
Enabled default project targets supply the Agent and directory; multiple targets
remain selectable. A project without targets allows manual selection. Changing
the computer clears the previous directory. Offline computers and Agents cannot
be selected. The form survives Activity recreation and folding via ViewModel
state. Process death can discard an unsubmitted form.

Creating first calls the existing conversation endpoint with `initialize_only: true`,
`cwd`, and the optional project ID. No prompt or client-chosen session ID is sent.
The Manager performs ACP `session/new`, persists the native binding, then emits
`session` and `done` SSE events. Android verifies the returned node/Agent/session
identity before applying the optional name and roots through the existing
session-record POST. Only a confirmed native binding opens the conversation.

The normal creation form uses Manager transport and server approval defaults.
Existing encrypted sessions retain their encrypted-command initialization and
key-approval boundary; no E2EE session is converted to plain transport.

Duplicate taps and dismissal during creation are blocked. Once a server-assigned
ID arrives, it is kept even if the stream or the metadata update subsequently
fails. **Check status** reads that identity and verifies its project, working
directory and native binding; it never repeats initialization. If the response
was lost before an ID arrived, the user is directed to refresh Sessions and
inspect the list. There is no guessed identity or automatic mutation replay.
Closing an uncertain form does not delete any server session. If only the
optional metadata update failed, recovery can open the initialized session with
its server-provided name.

## First-message 409 fix (2026-10-16)

The original form only registered a session record. Manager's
`resolveConversationSession` rejects any normal prompt whose record has no
`native_id` with HTTP 409 (`session has no native ACP session id`). The original
fixture accepted prompts without enforcing that contract, so it missed the bug.
The initialization flow above follows `handleConversation` / `initialize_only`
in the current sibling Manager checkout and does not need gateway changes.

Old record-only sessions cannot be initialized in place through the current
conversation API (`initialize_only` rejects an existing `session_id`). They show
an actionable message to create a new session; the client never silently moves
or resends a user's prompt. Bounded JSON HTTP error reasons are displayed, with
generic fallbacks for malformed, oversized or HTML gateway responses.

Regression fixtures cover initialization before metadata, server-assigned IDs,
no prompt during creation, missing native bindings, interrupted SSE before and
after identity assignment, failure after initialization, duplicate taps, and
preserving E2EE transport during recovery. The UI fixture enforces initialized
identity on the first prompt and waits for the canonical response.

Verification for this fix: build, lint and all 72 JVM tests passed. The isolated
emulator's `createsSessionFromProjectAndPreservesFormOnRecreation` test passed,
including the initialization/first-prompt contract and canonical response. APK
was installed on Pixel 11 Pro Fold with `adb install -r`, and the installed
base.apk hash matched `artifacts/session-init-409-2026-10-16/PaxWorkspace-debug.apk`:
`6dd1077c1af7274e02a51c83df09659ce90af130db94d0f450ebc0dc3db3d923`.
The app launch returned `Status: ok`. No real user prompt was replayed; production
Agent response acceptance remains a user-device check. The validated fix is included in the main submission.

## Verified contracts

Sources in sibling `pax-manager-android-native`, base `a3d01e4`:

- `internal/manager/node_idl_handlers.go`: CreateNodeAgentSession binds the route.
- `internal/manager/domain/models.go`: CreateSessionRequest includes session_id,
  primary_project_id, workspace_roots, source, name and pax_config.cwd.
- `internal/manager/userapi/service.go`: node-scoped Agent list and create response.
- `internal/manager/userapi/project_target.go`: explicit project target collection.
- `internal/manager/storage/postgres_store.go`: persists the working directory.
- `internal/manager/conversation.go`: `initialize_only` creates ACP/native binding
  with request `cwd`; registered records without a native ID reject prompts (409).

The WorkOS native gateway needs these three exact existing Manager routes:

- GET `user/self/nodes/:node_id/agents`
- GET `user/self/projects/:project_id/targets`
- POST `user/self/nodes/:node_id/agents/:agent_id/sessions`

No changes to authentication, credentials, Access policies, database schema or
approval defaults. The existing signed user identity and Manager resource
permission checks remain in effect.

## Delivery status

Gateway code, TypeScript checks, 163 Worker tests, formatting and production
configuration dry-run pass. The initial deployment request was rejected by
automatic approval review. The user then explicitly approved production
publication. Gateway version `b7ed9059-e252-4619-b34d-f00a2d856478` was deployed
and verified active at 100% on 2026-10-14 (America/Los_Angeles). Previous version:
`59e2dd0e-1ff9-4c61-b20b-50cac28a92a9`.

Live anonymous checks against all three newly allowed routes returned 401;
POST to project targets, which is not allowed, returned 404. No production
session was created by these probes.

Android verification passed: 44 JVM tests, 17 instrumentation regression tests,
and an additional focused creation/send test at 1080x2400, density 420 and 130%
font scale. Build and lint passed. Expanded and compact form screenshots were
visually reviewed. Fixture tests include form recreation, default project target,
creation and first-message dispatch; no production session was created.

Artifacts: `artifacts/new-session-2026-10-14/` (APK, logs, unit reports, lint and
screenshots). APK SHA-256:
`f2760580ccb92cade4a1f4e4f47af463c0e896abb71b42348991b9cd6a25c8b2`.
Version remains unpublished 0.1.13 (14). Installed over Wi-Fi on Pixel 11 Pro Fold
with `adb install -r` (success, device update time 2026-10-14 21:59:12). The
installed base.apk SHA-256 matches the artifact above; existing data is retained.
MainActivity launch intent was delivered. The phone was dozing with
NotificationShade focused, so authenticated form interaction was not verified on
the physical device. The full creation/send flow was validated with isolated
fixtures, not a production session.
