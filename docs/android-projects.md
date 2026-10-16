# Android projects and Home views

Home has a pinned, mutually exclusive Sessions / Projects pill control with
radio semantics and 48 dp minimum touch targets. It remembers the selected view
when leaving Home and across activity recreation. Settings retains device and
account controls; projects belong to Home. The plus action creates a session or
a project according to the selected view.

The selected pill slides with a critically damped spring; label colors fade over
180 ms with stable font weight. Selection takes effect immediately, and repeated
taps retarget the ongoing animation. Indicator geometry follows the current
layout width and direction, including fold/resize and RTL layouts.

New project requires a name. The form survives activity recreation, prevents
duplicate taps, validates the Manager's 100 UTF-8-byte limit, and disables editing
while submitting. Success adds the returned project immediately. Selecting a
project shows its sessions; New session then preselects that project. Projects
are logical session groups; creating one does not create filesystem folders or
configure project targets.

The existing Manager contract is POST `/api/v1/user/self/projects` with
`display_name`, returning `data.project`. Source: sibling
`pax-manager-android-native/api/pax_manager.thrift` and
`internal/manager/userapi/project.go`. The server assigns project IDs and has no
client idempotency contract for this operation. Unknown outcomes disable repeat
submission and direct the user to a fresh Projects list before any deliberate
new attempt. No name-based identity inference or automatic POST retry is used.

The native gateway allows this exact authenticated POST. Current-user, WorkOS
identity and Manager ownership checks still apply. No project deletion, update,
target mutation or cross-user route is added.

NewProjectControllerTest covers the request/envelope, UTF-8 limit, duplicate
taps, errors and unknown outcomes. WorkspaceFlowTest covers creating a project,
form recreation, persisted Home selection, and opening New session with the
created project preselected. Screenshots use isolated emulator fixtures.

## Verification, 2026-10-14

- Debug build, lint and 54 JVM tests passed.
- All seven workspace UI flows passed on the foldable emulator. Project creation
  also passed on a 1080 x 2400 phone viewport at 1.3x system text size.
- Expanded and compact screenshots were inspected. Gateway typecheck/build and
  165 tests passed; production version 6a35eca0-b8a9-4d65-b59f-8259c3903394 is
  active at 100 percent. Anonymous creation requests return 401 and unrelated
  mutations return 404.
- The APK and screenshots are in `artifacts/projects-2026-10-14/`. Creation was
  exercised against isolated fixtures, not by adding a test project to the user's
  production account.
- Wi-Fi update completed on Pixel 11 Pro Fold at 23:00:53, retaining data and
  development version 0.1.13 (14). Installed APK SHA-256 matched
  `de324c4d5a1f760c91f22549309cab2c3b3cdf7abedd681e69ee3242c0ab8cda`.

## Selection motion follow-up

- Debug build and lint passed after adding the sliding indicator and color fade.
- The existing project creation / Home selection / activity recreation UI flow
  passed on the isolated emulator; endpoint screenshots were inspected.
- Build log, UI log, screenshots and the updated APK are in
  `artifacts/home-switch-motion-2026-10-14/`.
- Wi-Fi update succeeded on Pixel 11 Pro Fold. Installed APK SHA-256 matched
  `21b95812a5bc6755c4968eab3a1495ec222a394c96a7cb6aa4b33a01b04fdc1e`.
