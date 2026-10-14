# Android visual refinement — 0.1.11

The workspace now uses a quieter neutral palette, consistent surface levels,
48 dp action targets and clearer separation between content and controls. The
existing NASA Earth welcome scene and signed-in zoom transition are retained.
A small orbital mark connects the login, empty detail pane and app identity.

## Mobbin references

These iOS screens were retrieved and visually reviewed through Mobbin MCP.
Their information hierarchy informs native Compose controls; their platform
navigation and unsupported features are not copied.

- [Linear Mobile — issue list](https://mobbin.com/screens/2b4047d8-3247-40db-8e3e-9e0d6b982f91):
  compact title/status hierarchy, restrained surfaces and secondary labels.
- [ChatGPT — conversation entry](https://mobbin.com/screens/be5b6790-060a-41f9-a4ff-7064c91603ca):
  content-first conversation, a single rounded composer and clear send action.
- [Perplexity — settings](https://mobbin.com/screens/fadb2a57-2b42-4c9c-ac12-b15694d010ad):
  identifiable account section, grouped navigation rows and subordinate metadata.

High-resolution references are retained only in ignored local validation assets.
No third-party screenshots or app branding are bundled in the APK.

## Changes

- Session rows show title, safe preview, runtime status and project. Encrypted
  sessions continue to hide previews. Selection is explicit in the wide layout.
- Search has a clear action, an IME search action and a distinct no-results state.
  Moving between tabs clears text focus and dismisses the keyboard.
- Conversation messages distinguish user and agent; the composer grows for
  multiline drafts and places a 48 dp send/stop action alongside the input.
- Settings use account and workspace groups. Computer, project and sign-out
  symbols communicate their actual functions. Secondary destinations support
  both toolbar and system back navigation.
- Approvals have a pending count, readable request hierarchy and retain explicit
  decision confirmation. Empty approvals and empty project/device lists provide
  contextual copy instead of bare placeholder text.
- Primary actions remain light, secondary actions retain visible borders, and
  toolbar actions use circular filled surfaces. Connection indicators always
  include a text label in addition to their color.

## Validation

The fixture workflow exercises search/clear, project filtering, settings/device
navigation, approval confirmation, sending, draft restoration and compact/wide
window transitions. A populated review fixture is synthetic, not a live account.
It also checks 150% system font sizing and records screenshots with the keyboard.
Existing welcome-transition and both Earth renderer tests remain in the suite.

Final validation: **31 JVM tests and 12 instrumentation tests passed**, with no
skips or failures; debug build and Android lint passed. Screenshots were reviewed
in compact and expanded windows, with the keyboard, and at 150% font scale.

Local evidence is under `artifacts/validation-0.1.11/`. This is a UI refinement;
these fixture checks do not establish a new production gateway integration run.

The validated APK was installed on the authorized Pixel with `install -r`.
Version 0.1.11 (12) and the installed APK hash were verified against the local
artifact. The phone remained in DreamActivity, so physical visual acceptance
is still pending. Local package SHA-256:
`53de1fba0e2cd3ab01d68b782216545339af718bfaed2df95a193e3a7075e862`.
