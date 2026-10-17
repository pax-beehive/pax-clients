# Android conversation attachments

Android preserves ACP `image`, `audio`, `resource`, and `resource_link` content
blocks in live frames and durable history, including attachment-only messages
and tool output. History extracts `raw_json` and `parts[].payload_json` and
recognizes `artifact` parts with `artifact-publication://<id>/<ref>`. The
`pax_artifact_publication` tool result marker provides a card during streaming.
Repeated copies of one block within a history record are deduplicated. Images
arriving between text chunks keep a separate timeline segment.

## Interaction

- Images load on tap. Raster previews support a full-screen view, pinch zoom,
  reset zoom, Save file and Open with another application.
- File cards show filename, MIME type and downloaded size. Plain text, Markdown
  and JSON have a bounded text preview. PDF and other files can be saved or
  opened with a compatible installed application. There is no inline PDF,
  audio or video player in this change.
- Published tool files remain visible above the collapsed activity details.
- A pending upload offers Retry. Errors remain on the file card and do not
  disconnect the conversation. Reloading a card obtains a fresh download ticket.
- Save file uses Android's document picker. Open with exposes only the selected
  private-cache file via a non-exported FileProvider with temporary read access.
  Shared cache files older than one day are removed on the next Open with.
- Downloads are limited to 25 MiB; embedded payloads to 4 MiB encoded characters.
  Larger deliverables should be accessed through another client. Remote content
  is not automatically fetched merely because a message arrives.

## Local files and publishing

A machine path is not a phone-accessible URL. Agent-produced files use the
existing paxd `publish_artifact` MCP tool, whose input is `path` and optional
`title`. The tool stages a file snapshot, publishes it through Manager/object
storage, and produces the publication marker rendered by Android. The client
does not add arbitrary remote filesystem reads or broaden directory browsing.
An unpublished `file://` link explains that the agent must publish the file.

Example user instruction to the agent: "Generate a chart and call
publish_artifact with its path so I can preview it in this conversation."

## Verified contracts and authentication

Sources: sibling `paxd/cmd/paxd/mcp_conversation.go`,
`paxd/internal/artifactpublisher`, and `pax-manager-android-native`:

- `internal/manager/artifact_publication_reconcile.go`: tool marker and durable
  `artifact_uri` projection.
- `internal/manager/artifact_publication_content.go`: available, pending (202),
  and failed (409) state; finalized content with a signed URL.
- `internal/manager/artifact_handlers.go`: existing artifact content ticket.
- `internal/manager/attachment_content.go`: user attachment ticket with
  `?ticket=1`, avoiding the browser redirect response.

Native gateway additions are only GET on:

- `user/self/artifact-publications/:id/content/:ref`
- `user/self/artifacts/:id/content/:ref`
- `user/self/attachments/:id/content`

WorkOS authentication, signed Manager user identity and owner/resource checks
remain enabled. Publication and artifact tickets are additionally matched to
the current session before a file request. No upload, publication mutation,
arbitrary path or other-user route is allowed by this change.

The file request uses a fresh request without API Authorization or cookies.
Only HTTPS URLs without embedded credentials are accepted; redirects are not
followed. Response length is bounded even for chunked bodies. HTML/SVG are never
executed in an in-app web view. Decoded media and preview images are not persisted
in shared image caches; exporting a file is an explicit user action.

E2EE frame decryption remains in SessionRuntime. Inline media inside decrypted
frames uses the same renderer without plaintext fallback. This change does not
implement the web client's separate chunk-encrypted attachment manifest protocol
or add attachment upload to the mobile composer.

## Validation

Build, lint and all 77 JVM tests passed. Eight distinct isolated-emulator UI
checks passed across attachment cards/image decode/full preview, Markdown image
compatibility, conversation behavior and session creation. Synthetic screenshots
were reviewed. Gateway typecheck and all 176 Worker tests passed.

Gateway version `d80db30f-1e37-4ffa-aff0-490100740e8c` was deployed and verified at
100%. Live anonymous GET probes returned 401 for all three content routes;
POST content and other-user paths returned 404. No real user file was fetched
and no real user prompt was sent during validation.

The APK was installed with `adb install -r` on Pixel 11 Pro Fold, retaining
existing data. The installed base.apk SHA-256 matches the artifact:
`38b134996035e98d68ae82c20b02e84511b637713922d776598cc6b64dbeb995`.
Artifacts and screenshots: `artifacts/android-attachments-2026-10-16/`.
The app launch returned Status: ok. Production Agent file delivery still needs
user acceptance; this validation used synthetic media and MockWebServer.

The gateway runtime is already deployed; Manager and paxd required no changes.
The validated Android and gateway sources are included in the main submission.
