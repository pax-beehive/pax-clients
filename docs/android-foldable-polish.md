# Foldable workspace refinement

The unfolded workspace previously repeated the same large circular refresh icon
in both panes, although one reloaded workspace data and the other reconnected the
selected session. Both panes also kept phone-style navigation and card density.

## Reviewed references

These Mobbin screens were retrieved and visually inspected for this refinement:

- [Microsoft Teams — chat workspace](https://mobbin.com/screens/a71e4bff-2627-4edc-af47-0f73476700c5):
  a subdued conversation list, clear selected row and a separate conversation header.
- [Upwork — messages](https://mobbin.com/screens/9aa6145c-770a-4149-8e35-9f1ccb0ccc72):
  list/detail hierarchy and secondary conversation actions in an overflow menu.

These patterns inform native Compose controls. No reference images or third-party
branding are bundled in the app.

## Behavior

- The sidebar header places **Refresh workspace** immediately before **Hide sidebar**.
  Refresh is a labeled Hold button: a short tap does nothing, holding fills the
  button until the platform long-press threshold triggers one refresh, and releasing
  early or dragging away cancels. It is disabled while loading and omitted on Settings.
- **New session** sits at the right of the **Recent sessions** heading.
- The conversation header uses **Session actions**, with a named **Reconnect session**
  menu item. Reconnection is disabled during an active reply or connection attempt.
  An interrupted connection exposes a labeled **Reconnect** action above the composer.
- On expanded windows without a separating hinge, the sidebar header offers
  **Hide sidebar**; the conversation header offers **Show sidebar** only while the
  list is hidden. Hiding the list gives the conversation the entire window width. **Close conversation** remains in the session menu; compact
  windows retain the back arrow.
- Dual-pane lists use a quieter background, transparent unselected rows, shorter
  previews and no redundant row arrows. Selected rows retain a distinct surface
  and expose selection to accessibility services.
- Tapping the selected session again does not restart its connection or interrupt
  its reply. Search focus is still dismissed.
- Consecutive reasoning and tool operations from one turn share a collapsed
  **Activity · N steps** row. Opening it reveals per-step disclosures; full text
  and command output remain selectable, with long output scrolled inside the detail.
  Failures stay visible on the summary, and approvals remain separate actionable cards.
- Stored thought message types and thought parts are normalized separately from
  assistant prose, matching the web client's history conventions. Mixed messages
  preserve part order and keep answer text visible. Replacing or prepending history
  retains the mapping of split parts so stale reasoning cannot survive replacement.
- Agent replies use a subtle gray bubble with a visible outline, 20 dp corners
  and 16 dp content padding. This separates replies from the chat background
  without clipping text at the corners. User messages use blue-gray filled,
  right-aligned bubbles with a **You** label; Agent replies keep their neutral
  outlined surface and **Agent** label. Empty-detail titles explicitly use the theme's foreground
  color for contrast on the dark background.
- The existing hinge-aware geometry determines whether both panes are visible;
  toolbar behavior does not guess from device names or width alone. Pane identity,
  draft state and list state remain stable through resizing.
- Timeline tail-following schedules a scroll for the next measure instead of
  forcing nested measurement while Scaffold composes the conversation. This fixes
  a `performMeasureAndLayout called during measure layout` failure caught in the
  streaming fixture during validation. See Android's
  [requestScrollToItem contract](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState#requestScrollToItem(kotlin.Int,kotlin.Int)).

## Validation

Initial foldable refinement verification on 2026-10-14:

- Debug APK build and Android lint passed.
- 34 JVM tests and 14 instrumentation tests passed, with no failures or skips.
- Instrumentation ran only on `emulator-5584`, AVD `pax-foldout-integration`, with
  the synthetic fixture account. It covered search, project filtering, approvals,
  reconnect/close menus, selection semantics, sending and streaming, send motion,
  draft restoration, window resizing and Activity recreation.
- Screenshots were visually reviewed at compact and expanded sizes, with the
  keyboard, at 150% font scale, and with the empty detail pane.

Evidence and the local review APK are in
`artifacts/foldable-polish-2026-10-14/`. The APK retains development version
0.1.13 (14); this is an unpublished local build, not a new release.
SHA-256: `e17dd091498f419f3182d82fbe7000e028e22cb52dd522c4b448b90fecc1699b`.

No physical device was connected during this run. Physical fold/unfold acceptance
and production transport validation remain separate from these fixture checks.

### Reply bubble follow-up

After feedback that the background-colored replies were difficult to distinguish,
Agent replies gained a separate gray surface, outline and padded rounded shape.
The debug build, lint and all 14 existing UI tests passed again. Compact and
expanded screenshots, including 150% text sizing, were visually reviewed; the
text stays inside the bubble corners. No new unit tests were needed for this
presentation-only change.

The updated unpublished 0.1.13 (14) review APK and screenshots are under
`artifacts/agent-bubbles-2026-10-14/`.
SHA-256: `20d78fb289bbd65065a91bc4c6dd6143907876d7f218a28bbab95689e1935a71`.

At the user's request, this APK was subsequently installed over Wi-Fi debugging
on the Pixel 11 Pro Fold with `adb install -r` (success, update time
2026-10-14 20:33:34). The installed `base.apk` SHA-256 matches the review APK
above; version remains 0.1.13 (14). Existing app data was retained and the launch
intent was delivered to MainActivity. Physical visual acceptance remains pending.

### Activity summaries and author distinction

The follow-up groups verbose activity and gives user messages a distinct blue-gray
fill and **You** label. The send overlay transitions to the same fill. Its landing
geometry uses unclipped coordinates and ignores empty/offscreen initial positions
while the multiline composer settles, avoiding a zero-width animation target.
See the Android [layout coordinate contract](https://developer.android.com/reference/kotlin/androidx/compose/ui/layout/LayoutCoordinates).

Final verification passed: debug build, Android lint, 38 JVM tests and 16
instrumentation tests, with no failures or skips. Tests covered grouped activity,
nested full-output disclosure, visible failure states, history replacement,
multiline send motion and fold cancellation. Expanded screenshots were visually
reviewed at normal and 150% font scale.

The unpublished review APK, screenshots and test reports are under
`artifacts/activity-summary-2026-10-14/`.
SHA-256: `2504f3e7ea70b986600ac5b944181bd76c7cceb9e3ed91490e343098a062f3d6`.

Installed this build on the Pixel 11 Pro Fold through Wi-Fi debugging with
`adb install -r` (success, update time 2026-10-14 21:48:24). The installed
`base.apk` checksum matches the artifact above. Version remains 0.1.13 (14),
existing app data was retained, and MainActivity was brought to the foreground.
Physical visual acceptance remains pending.

### Collapsible sidebar

The sidebar header provides a 48 dp collapse button on expanded, unobstructed
windows. While collapsed, a 48 dp restore button appears in the conversation header. The selected conversation, unsent draft, list filters and
scroll state remain in the same composition when the list is hidden. The sidebar
choice survives Activity recreation and compact-to-expanded resizing. Closing
the conversation still exposes the workspace list, even when the remembered
sidebar preference is collapsed.

Separating or occluding hinges keep their existing safe pane geometry; the toggle
is omitted there. Compact layouts keep their existing navigation. A hidden Devices
pane no longer intercepts the conversation's system Back action.

Validation: debug build and lint passed, with 66 JVM tests. The dedicated foldable
emulator passed the targeted conversation flow covering width gain, toggle
restoration, unsent draft preservation, compact/expanded resizing and Activity
recreation. The collapsed screenshot was visually inspected. Physical fold/unfold
is separate from emulator resizing coverage.

Installed the resulting debug APK on the connected Pixel 11 Pro Fold with
`adb install -r`, preserving application data. Launch returned `Status: ok`;
the installed APK SHA-256 matches the local build:
`75a8fd77775e49e6da0fd71624fabaae5d03f7203934ed43ce80b686fd3a25b9`.

### Sidebar action placement and hold-to-refresh

The collapse button moved into the sidebar header, with refresh immediately to
its left. The new-session button moved to the right of Recent sessions. The
conversation header shows only the restore control while the sidebar is hidden.
Refresh now uses a labeled Hold pill with a filling indicator and the system's
long-press timing. Short taps and canceled gestures never dispatch refresh; a
continuous hold dispatches once, and loading disables the control. Accessibility
exposes the named long-click action.

The debug build, lint and 66 JVM tests passed. Three isolated emulator UI tests
passed, covering short/long/canceled/disabled gestures, exact control placement,
sidebar width and persistence, draft preservation, resize and Activity recreation.
The expanded screenshot was visually inspected.

Updated the connected Pixel 11 Pro Fold with data preserved; launch succeeded.
Installed and local APK SHA-256 both match
`1aa58ffd6093d2f8603f0328113750b5f9df5e64875d4cbfb411cc3926df6c36`.

### Charcoal palette and reading hierarchy

The workspace now uses neutral charcoal surfaces instead of blue-gray panels.
Blue remains on primary actions and the subdued selected-session background;
agent and project labels use neutral text so green consistently identifies live
status. Assistant bubbles retain their shape with a quieter border, and user
bubbles retain a distinct desaturated blue fill.

System sans-serif text remains scalable. Body text is 16/24 sp, session list
titles are 15/21 sp, and the conversation title is 18/24 sp semibold. The
workspace title uses medium weight. The Sessions/Projects switch uses a neutral
selection indicator and smaller corners; search and hold-to-refresh fills are
lighter. Refresh, collapse and new-session placement and 48 dp action targets
are preserved.

Design references reviewed in Mobbin: [ChatGPT hierarchy](https://mobbin.com/screens/fa707eb9-8ecd-48e1-b94d-647c03a08bc6),
[Linear list hierarchy](https://mobbin.com/screens/fd1b4d88-f021-49a3-98af-4cd3a87e1d29),
and [Claude dark reading surface](https://mobbin.com/screens/ed3e4d7f-e3e5-44e8-a0ad-649a958ce5fa).
Typography values are our implementation choices, not measurements from those
screens. Calculated solid-color text contrast is 14.41:1 for body text and 7.79:1
for secondary text on the low surface; this is not a full accessibility audit.

Validation passed: debug build, lint, 66 JVM tests and four isolated emulator UI
tests covering refresh gestures, sidebar placement/collapse, draft preservation,
resizing, Activity recreation and 150% font scale. Compact, expanded, collapsed
and large-text screenshots were visually inspected. No new tests were added for
this presentation-only change. Screenshots and the review APK are saved under
`artifacts/charcoal-ui-2026-10-15/`. APK SHA-256:
`bfe349af930da99d3aa81480facbad414b935705d20a984277a1f5bfffb5e128`.

Installed on the connected Pixel 11 Pro Fold using `adb install -r`, retaining
app data. Installed APK checksum matches the artifact above and launch returned
`Status: ok`. Physical-device visual acceptance remains separate from the
emulator screenshot review.

### Agent and device presence above messages

Agent and device bylines now share 12/18 sp text, 16 dp icons and baseline
alignment. Each identity includes a status dot and explicit Online, Offline or
Unknown text, with independent accessibility state descriptions. Entire identity
groups wrap on compact windows; long names truncate while status stays visible.
Session details also show both statuses.

The selected conversation reads manager-reported node and agent reachability
immediately, then polls after a 10-second interval while its lifecycle is started.
Leaving the conversation or backgrounding cancels collection; resumption starts
unknown. Reads time out after eight seconds and failed/missing reports become
unknown. An online agent report is suppressed to unknown when its device cannot
be confirmed online, preventing stale agent heartbeats from presenting green
status on an offline machine. These are current connection states, not activity
states or historical status at message creation.

Validation: build, lint, 68 JVM tests and four isolated emulator UI tests passed.
Coverage includes ID matching, missing/error/offline reports, stale agent
heartbeats, polling cancellation/resumption, independent accessible statuses,
Chinese/Latin baseline alignment at 150% font scale, and the existing foldable,
draft and approval flows. Compact, expanded and large-text captures were reviewed.
The APK and screenshots are under `artifacts/presence-ui-2026-10-16/`.
APK SHA-256: `4c5a46ee4cfd6e3cd41f001454c786c6c7923f23af4fc37102d945c76530bce6`.

Installed on the Pixel 11 Pro Fold with `adb install -r`, preserving app data.
The installed APK checksum matches the artifact and launch returned `Status: ok`.
Live-device visual acceptance is separate from emulator fixture validation.

### Workspace interaction motion

Sidebar toggles slide and fade over 240 ms, with the conversation translating
alongside them. The detail pane uses full width during motion and settles to its
split width at the end of expansion, so text is not reflowed on every frame and
the viewport's right edge stays covered. Window/hinge changes snap to safe
geometry. Both pane compositions and draft state remain mounted; toggles can
reverse while moving.

Session selection colors transition over 180 ms, and a newly selected session
fades in over 180 ms. The old session is disposed immediately instead of keeping
two live conversations during a crossfade. A small Working indicator breathes
only while the connected runtime is busy; pending approvals use a static amber
Needs approval label. Online presence dots remain steady.

Activity groups and tool details expand/collapse over 180–200 ms and rotate their
disclosure arrows. Streaming body content does not receive size animation.
User-initiated refresh shows progress and a brief success check after a successful
loading cycle; failed refreshes do not show success. Finite transitions use
Compose's system duration scale, and the breathing dot becomes static when
Android disables animators.

Validation passed: build, lint, 68 JVM tests, six focused motion/refresh UI tests
and seven conversation/foldable UI tests. Controlled-clock captures verify an
intermediate sidebar position, rapid reversal, preserved drafts and only two
measured content widths; zero duration scale completes the toggle immediately.
Work/approval/idle and refresh success/failure transitions are covered. Existing
streaming, history anchoring, disclosure details, large text, fold/resize and
Activity recreation checks passed. Mid-motion and final UI captures were reviewed;
this is separate from physical-device motion acceptance.

Review APK and captures: `artifacts/workspace-motion-2026-10-16/`.
APK SHA-256: `16e310a1d9a68b4ef8497ba14e7be8fbb47457c9643fba5ce03a818710f7f39c`.

Installed on the Pixel 11 Pro Fold using `adb install -r` with data preserved.
The installed checksum matches the reviewed APK; cold launch returned `Status: ok`.
