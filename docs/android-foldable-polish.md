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

- The list header is now **Sessions**, with one unfilled, 48 dp **Refresh workspace**
  action. It is disabled while loading and omitted on the Settings landing page.
- The conversation header uses **Session actions**, with a named **Reconnect session**
  menu item. Reconnection is disabled during an active reply or connection attempt.
  An interrupted connection exposes a labeled **Reconnect** action above the composer.
- When both panes are visible, the detail header omits the back arrow and provides
  **Close conversation** in its menu. Compact windows retain the back arrow.
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
