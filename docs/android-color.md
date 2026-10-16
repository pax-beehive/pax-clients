# Android workspace color

The dark background remains neutral. Raised cards use a subtle slate tint, with
color reserved for information and interaction:

- Blue: primary actions, the sliding Home selection, selected navigation and
  session rows, links and form focus. Existing blue user bubbles remain distinct.
- Mint: device icons, Codex bylines and empty-state artwork. Connected/running
  status and completed activity use a separate soft green success token.
- Warm peach: project folders/tags and Claude bylines. Pending approvals use an
  amber status and badge; errors retain red.
- Body text remains near white, secondary text a readable cool gray. Device names
  stay secondary in the conversation byline. Labels/icons accompany color so it
  is never the only indicator of selection, identity, or status.

WorkspaceTheme.kt owns the palette and semantic status tokens. Components consume
these tokens instead of overriding primary actions with white/gray controls.

Verification artifacts live in `artifacts/workspace-color-2026-10-14/`.

## Verification, 2026-10-14

- Debug build and lint passed.
- Existing project creation/Home selection and navigation/layout gallery UI
  tests passed on the isolated emulator, including 1080 x 2400 compact,
  2200 x 2480 expanded, and 1.5x system text layouts.
- Inspected project, compact conversation and expanded large-text screenshots.
- Calculated 18 enabled text/background pairs; the lowest checked contrast was
  5.31:1 (secondary text on the selected session surface). Values are recorded in
  `contrast.txt`; this is a targeted palette check, not a full accessibility audit.
- Wi-Fi update succeeded on Pixel 11 Pro Fold with data retained. Installed APK
  SHA-256 matched `8e3720327b18113b3d3e4b84f775bd47fdc476b4ac6e9bec92364ec2c98279f7`.
