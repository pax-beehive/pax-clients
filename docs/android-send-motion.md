# Android send interaction — 0.1.13

## Experience

The rounded composer becomes the sent message: its surface lifts, narrows and
settles into the right-aligned bubble over 420 ms. The input contracts with a
critically damped spring, the send button gives a small press response and light
haptic feedback, and the stop action uses a filled square. Short messages now
wrap their content instead of occupying a fixed-width block.

One quiet dot breathes while a submitted prompt has no response content. It gives
way to the first thought, activity or answer. New rows fade in over 160 ms; streaming
updates do not restart the fade or animate list placement on every chunk.

## References

Reviewed Mobbin's ChatGPT iOS flows:

- [Replying to a message](https://mobbin.com/flows/cc9444b5-9076-4664-8990-768084bb469d):
  rounded composer, compact right-aligned message, restrained controls.
- [Recording voice](https://mobbin.com/flows/58d74cd6-a372-4e6f-97a1-6f43a1dfc269):
  capsule composition and clear circular action.
- [Chatting on a group chat](https://mobbin.com/flows/b66f57b7-53ef-4a1f-b575-fb9400a749a4):
  quiet waiting feedback beneath a sent message.

These were static screen references. The flight and dot animation are our own
Compose implementation; no ChatGPT branding or third-party assets are shipped.

## Implementation boundaries

Sending starts before the presentation animation. The ViewModel returns the
accepted local message ID; the runtime uses that same request ID for the immediate
local echo and encrypted prompt identity. The overlay never dispatches, delays,
retries or acknowledges a network operation.

The real timeline row provides destination geometry and retains semantics; only
its pixels are hidden during flight. Canonical history can replace the local ID:
matching then requires the observed backend turn, user role and exact prompt text,
never text alone. A missing destination times out after 250 ms and reveals the row.

Text is measured at the two endpoint widths, not on each animation frame. Shaped
fragments move around neighbouring lines when wrapping changes, avoiding the double
text produced by a crossfade. The overlay is clipped to the session pane and is
absent from accessibility semantics. The input retains system font sizing and the
send button has a 48 dp target; the waiting dot has a polite accessibility label.

System-disabled animations skip the flight and use a static dot. A pane width or
horizontal position change ends flight immediately. Long drafts with hidden input
lines, off-screen destinations, code fences and bidirectional text use normal
message insertion, preserving the full text without an inaccurate flying layout.
A rejected send keeps the draft. Existing reconnect draft restoration is preserved.

## Verification

Validation uses synthetic conversations on the dedicated integration emulator.
The new motion cases cover immediate dispatch, canonical ID replacement during
flight, fold-time cancellation without resending, disabled motion, rejected sends,
Chinese multiline text with a fast response, and a long scrolling draft. Existing
conversation and transport cases cover real streamed fixture chunks and editing
while a reply is arriving.

Frame captures under ignored `artifacts/validation-0.1.13/` document lift, midpoint,
landing, waiting and first reply. These establish emulator appearance and behavior;
they are not a physical-device frame-rate benchmark or user acceptance.

Final checks: `assembleDebug`, `testDebugUnitTest` (34 passed), `lintDebug`,
and `connectedDebugAndroidTest` (22 passed, 1 opt-in live gateway test skipped).
No production gateway or transport protocol was changed for this iteration.

Version **0.1.13 (14)** was installed wirelessly on the Pixel 11 Pro Fold with
`adb install -r`, preserving application data. The installed base APK SHA-256
matched the tested local debug artifact. The phone is ready for interaction
acceptance; no synthetic fixture was run against its account.
