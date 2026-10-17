# Mobile directory browsing and paxd upgrades

Android and iOS now expose device tools from Settings > Devices. New session
forms have a Browse device folders action after selecting a device. Manual path
entry and existing project targets remain available.

## Directory contract

`GET /api/v1/user/self/nodes/:node_id/daemon/directories`

Query parameters: `path` (empty means home), `after` (last folder name from the
previous page), and `show_hidden` (false by default). The manager authorizes node
access before forwarding `directories.list` over the existing control tunnel.
The result contains `directories: {home, path, parent?, entries, next_after?}`;
each entry has `name` and `path`. Errors remain control-query errors, not empty
successful listings.

The daemon confines browsing to the home directory of the account running paxd.
It uses Go `os.Root` for filesystem operations, so traversal and symlinks cannot
escape that boundary. Home has no parent action. Links resolving outside home
are omitted. Nothing reads file contents or recursively walks descendants.

Each request enumerates only the selected directory. It returns at most 100
folders in lexical name order. Enumeration uses bounded batches and retains at
most 101 candidate entries; large directories do not require holding the whole
listing in memory. Clients request another page only when Load more folders is
tapped. Directory changes between pages can alter the listing; refresh starts a
new enumeration. No persistent directory cache or background indexing is added.

Switching device invalidates the old selection. Canceled or stale responses
cannot populate another device's picker. Errors are retryable and never select
an unconfirmed folder. The restriction is on browsing; it does not redefine the
existing session working-directory contract or change existing project targets.

## Versions and upgrades

Existing paxd startup and 10-second heartbeats already include `paxd_version`,
`boot_id` and daemon phase. Manager persists the reported version. Device lists
now display it; offline devices show the last reported value.

Device tools fetch the platform-specific stable release through the authenticated
native proxy at `public/paxd/download`. Upgrade sends an explicit target version,
a stable command ID, `tag: stable` and `mode: when_idle` to the existing daemon
upgrade endpoint. It does not force interruption of active work.

Before dispatch, clients save the command ID and version in their secure vault,
keyed by account and device. Closing/reopening the page or restarting the app
resumes status checks without submitting a new command. Unknown network outcomes
also check the original command. Definite rejections and terminal failures are
shown separately from success. Polling stops after six minutes with a Check
status action; this sends no new upgrade request.

Success requires manager's `maintenance_confirmation.status == confirmed`, which
checks a new boot, running phase, and the requested version. An applied/accepted
command alone never counts as a successful upgrade. Existing paxd download,
checksum verification, staging, idle coordination and restart are reused.

## Source and rollout

- `../paxd`: home-confined directory query and tests.
- `../pax-manager-android-native`: Thrift contract, generated routes, authorized
  forwarding, native gateway routes and regression tests. Existing gateway edits
  in that worktree are preserved.
- This repository: Kotlin/Swift models, forms, device tools, and tests.

Manager and paxd changes were subsequently merged and released, and the approved
native gateway routes were deployed on 2026-10-16. The client changes are included
in this main submission; the Android debug APK was installed on the test phone.
No real-machine upgrade was submitted during client validation. Old daemons cannot
browse directories; manual entry remains usable. The directory boundary is the
paxd service user's home, which may differ from the interactive user's home when
paxd is installed under a different account.

## Validation (2026-10-15)

- Android debug build, 64 unit tests, lint, and isolated emulator picker/session
  recreation flows at a compact 1080 x 2400 display size.
- 28 Swift package tests and iPhone/iPad simulator flows covering folder selection,
  path return, version display and simulated confirmed upgrade; iPhone screenshots
  were inspected. These use synthetic fixtures, not a real daemon upgrade.
- Manager regeneration, formatting, lint, full Go tests and build.
- Native gateway typecheck, 49 targeted tests, and Worker dry-run build.
- paxd control, controlws, daemon and updater regression tests; full suite with
  reduced package parallelism. Full parallel execution exposed two timing-sensitive
  baseline tests, both passed in isolation. A pre-existing nonconstant fmt.Errorf
  was corrected so Go 1.26 vet can run the full suite.
