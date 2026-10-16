# Conversation identity

Assistant messages show the session's harness name (Codex, Claude, etc.) above
the bubble, followed by a muted computer icon and device name. The byline wraps
on narrow layouts and ellipsizes long names. Session actions > Session details
shows the full, selectable names in a scrollable dialog.

Identity uses existing Manager fields, with no additional requests:

- `AgentSession.agent_type` from the session response; known Codex and Claude
  aliases receive their product names. Other types keep their reported name.
  Missing types display Assistant rather than guessing from a title or ID.
- Match `AgentSession.node_id` to `Node.node_id`, then prefer the device's `name`
  over `hostname`, including offline devices. Missing devices display Unknown
  device. This is the agent's host, not the Android phone running this client.

Source: `pax-manager-android-native/api/pax_manager.thrift`, the `AgentSession`
and `Node` structs. Runtime messages and encrypted transport are unchanged.

ConversationIdentityTest checks JSON decoding, device association, Claude
aliases, offline hostname fallback, and unknown metadata. WorkspaceFlowTest
checks the byline and full details through the session flow; its layout gallery
covers compact, expanded and large-text configurations.

## Verification, 2026-10-14

- Debug build, lint, and all 57 JVM tests passed.
- Session identity/details/send flow and the compact/expanded/1.5x text gallery
  passed on the isolated emulator. The new dialog assertion was scoped to its
  window because the same device name is also present behind it.
- Compact, expanded large-text, and details screenshots were inspected.
- Logs, screenshots and APK are in `artifacts/conversation-identity-2026-10-14/`.
- Wi-Fi update succeeded on Pixel 11 Pro Fold with data retained. Installed APK
  SHA-256 matched `b2faf6b55cc7a2e461cc154f94461097fe2c85b1a5cd969d8b1d4932a6deb91e`.
