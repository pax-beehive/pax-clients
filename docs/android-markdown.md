# Android message Markdown

Agent replies use native Compose Markdown rendering. User messages retain their
literal text and existing send transition. Tool output stays in the compact
Activity disclosure. Raw response text is never rewritten in session storage.

Supported presentation includes headings, emphasis, strikethrough, inline code,
ordered/unordered and nested lists, task lists, block quotes, horizontal rules,
inline/reference links, GFM tables, and fenced/indented code. Code panels have
language labels, syntax highlighting for recognized languages, horizontal
scrolling, and exact code copying. Table cells wrap instead of truncating after
one line; wide tables scroll horizontally.

Replies expose one small copy icon (with a 48 dp touch target and an accessible label).
It copies the complete original Markdown; a brief checkmark confirms copying.
There is no Source/Rendered control. Incomplete streamed fences and
unknown languages stay readable. Each background parse owns its input and link
map; the previous rendering stays visible until the next parse completes.
Highlight results are applied only to matching source text. Above 20,000 code
characters highlighting is skipped; above 150,000 message characters the full
message is shown as plain text to bound parser cost. Exceptionally wide code
lines wrap to avoid exceeding native text layout constraints.

HTTPS images in paragraphs have alt text and a Load image action. They are not
requested until tapped, and are fetched without workspace credentials. Failed
images retain a readable label. Links open through Android for HTTP(S)/mailto;
executable and local URI schemes are rejected. HTML is shown as text rather than
executed. LaTeX and Mermaid retain their source; mathematical typesetting and
diagram generation are not implemented. Footnotes and other unsupported
extensions remain available in the copied original Markdown.

Implementation uses [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)
0.38.0 (JetBrains GFM parser), Highlights 1.0.0 and Coil 3.3.0. The renderer is
pinned to the existing Kotlin 2.2 toolchain. Custom code extraction preserves
indentation and removes only Markdown list/quote container markers. Custom image
handling supports inline and reference-style destinations.

Validation lives in MarkdownContentTest (code boundaries, streaming prefixes,
safe links, image references) and MessageMarkdownTest (rendered replies and icon-only copying,
copying, streamed replacement, extension fallback, phone/foldable/large-text
galleries), alongside existing conversation, send-motion and workspace tests.
Fixture instrumentation is restricted to the dedicated emulator.

## Local verification, 2026-10-14

- Debug build, lint and all 50 JVM tests passed.
- The six Markdown UI tests passed, including exact clipboard assertions and
  380 dp, 760 dp and 1.5x text galleries. Screenshots were visually inspected;
  this caught and fixed duplicated list/task/quote syntax markers.
- All four conversation and six workspace flow tests passed. All seven send
  motion tests passed separately after increasing the flight fixture height to
  leave room for the expanded foldable keyboard; clipped landings intentionally
  skip animation. No send-motion production behavior changed for Markdown.
- Debug APK installed with `adb install -r` over the existing Wi-Fi connection
  on Pixel 11 Pro Fold, keeping app data. Version remains 0.1.13 (14), a local
  development build. Screenshots are emulator evidence, not live-account visual
  acceptance on the physical phone.

Artifacts: `artifacts/markdown-2026-10-14/`. APK SHA-256:
`7e7b56cfd3a16fb78e3d5d70f6c3874a99c4d2eaf483f4ce098b8ebb838a77c9`.

## Numbered lists and message actions

Ordered lists reserve one number column per list, sized for its largest number.
Numbers align with the first text baseline, including when an item starts with a
heading. Wrapped lines and subsequent paragraphs keep the same body indentation;
nested lists retain independent numbering. The renderer receives the remaining
row width explicitly so long items do not push beyond the bubble.

The numbered-list galleries cover 9 → 10, wrapped Chinese text, a heading-led
item, nesting, 380/760 dp widths, and 1.5x text. Assertions compare actual text
baselines and body left edges. The existing clipboard test verifies that the
icon copies the unchanged Markdown and that Source/text-copy controls are absent.

Build/lint and all 13 focused Markdown/conversation UI tests passed. Phone,
foldable and large-text screenshots were visually checked. Installed over Wi-Fi
with app data preserved at 2026-10-14 22:48:32; installed APK SHA-256 matched
`063b3eb121ac190ee6ef0a1d3f8eedb2942e9864b319833d425642e92f6330ec`.
Evidence is under `artifacts/markdown-list-polish-2026-10-14/`.
