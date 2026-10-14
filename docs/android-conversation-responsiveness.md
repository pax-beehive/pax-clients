# Conversation responsiveness — 0.1.12

## Cause

The client discarded ACP chunks from its conversation POST and displayed the GET
observer instead. That observer publishes durable transcript replacements, not the
raw token stream (`pax-manager/internal/manager/session_observer.go`). It checks
storage every 500 ms; the Android client also waited 3 seconds between idle
observer requests. With no local prompt echo, sending looked unresponsive and
replies could appear as complete blocks after history synchronization.

Two presentation issues compounded this: typing state lived in the whole session
screen, and each streamed text change launched asynchronous code-fence parsing
followed by another layout. Scrolling targeted the top of the last message, and
crossing 12,000 characters unexpectedly collapsed an answer to 600 characters.

## Changes

- Insert a local user bubble before making the network request. It carries a
  sending caption until accepted. Encrypted prompt echoes use the same request
  identity, so replay replaces that bubble instead of adding another.
- Ordinary local sends render ACP frames directly from the POST. Suspend the GET
  observer during the send, reconcile with canonical history on completion, then
  start fresh observation. A generation guard rejects queued observer frames from
  before the send. Server echoes of the locally displayed prompt are not appended.
- Keep partial output and mark delivery unconfirmed when a stream ends without a
  terminal event. Require reconnect and never automatically resend a mutation.
- Keep draft state in the composer and collect session updates at the detail pane.
  Code-fence segmentation now produces one synchronous render per text update.
- Anchor the timeline at its tail as the reply grows or the keyboard changes the
  viewport. Preserve stable message positions while browsing earlier messages.
  Only tool/reasoning output is collapsed; growing answers no longer hit a length
  threshold. Live and canonical assistant messages share the same label.

## Validation

The protocol fixture sends one padded SSE event every 200 ms. Its first response
chunk is available at about 200 ms, a second at about 400 ms, and the POST ends at
about 1 second. The GET route uses the real durable-observer shape, not raw ACP.

Using the original runtime from `991c260`, the prompt and first response both
appeared in client state at 1,026 ms, after completion. With the fix, the final run
measured 2 ms for the local prompt and 211 ms for the first response. Tests also
require the second chunk to appear before POST completion, exactly one canonical
prompt/answer afterward, and no retry or loss of partial output on truncated SSE.
These are controlled local client-state timings, not physical display latency,
production network latency, or model time to first token.

Additional regression coverage exercises long growing answers, keyboard-sized
viewport changes, new message following, reading earlier history during updates,
input recomposition isolation, failed-send draft restoration, encrypted prompt
identity, real UI streaming with a concurrently edited draft, foldable layouts,
large text, login, approvals, and the existing welcome/Earth transitions.

Local test reports, captures and the baseline are retained in the ignored
`artifacts/validation-0.1.12/` directory. No server deployment or transport
contract change is required. Physical-device feel remains a separate acceptance
check from the local timing and emulator regressions.

Final checks: **34 JVM tests and 16 instrumentation tests passed**, along with
Android debug compilation and lint. Version **0.1.12 (13)** was installed over
wireless ADB on the Pixel 11 Pro Fold using `install -r` without a data reset.
The device APK and local debug APK have matching SHA256:
`e804aa2c4688aecaa2c1f58a25f3a8b5f7fbf22d09e4f0a071179b1bf45ae8b5`.
