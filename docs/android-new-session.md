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

Creating opens the returned session immediately and adds it to the local recent
list. The first message uses the existing runtime. The server-returned transport
is preserved, including the existing encryption/key-approval boundary. New
sessions use the server's default transport and approval settings.

The client assigns one manager session identity per form; it never supplies a
native/ACP session ID. Duplicate taps and dismissal during creation are blocked.
An unknown mutation outcome offers **Check status**, which reads that same ID
without repeating the POST. Recovery verifies the configured project and working
directory before opening, since the server write can partially succeed. Closing
an uncertain form does not delete any server session.

## Verified contracts

Sources in sibling `pax-manager-android-native`, base `a3d01e4`:

- `internal/manager/node_idl_handlers.go`: CreateNodeAgentSession binds the route.
- `internal/manager/domain/models.go`: CreateSessionRequest includes session_id,
  primary_project_id, workspace_roots, source, name and pax_config.cwd.
- `internal/manager/userapi/service.go`: node-scoped Agent list and create response.
- `internal/manager/userapi/project_target.go`: explicit project target collection.
- `internal/manager/storage/postgres_store.go`: persists the working directory.
- `internal/manager/conversation.go`: new runtime initialization uses pax_config.cwd.

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
