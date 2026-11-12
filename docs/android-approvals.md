# Android approvals in conversations

## Problem

Every approval for a session, including decided ones, was pinned below the newest reply.
Each card printed the full tool title, which for shell commands is the entire command
(heredoc PR bodies included), in title type, followed by all six options as stacked
buttons that stayed visible, disabled, after a decision. One request could fill the screen
and push the agent's answer out of view.

## Behavior

- **Pending** approvals stay at the tail as a compact card: the agent's one-line
  `description` as the headline, the command monospaced and clamped to four lines
  ("Show full command"), and **Deny / Allow once** side by side. The broader grants
  (session, agent, node, all agents) move to a "More" sheet that states each scope.
  Every option still goes through the existing confirmation dialog.
- While pending, the request's transcript notice is hidden so it is shown once.
- **Decided** approvals leave the tail. Within an activity run they collapse to one line
  below the activity box (for example `✓ Allowed once · gh pr create …`); several in one run
  share a single "N approvals" line. Tapping shows when it was decided, by whom (when it was
  you), the grant scope, and the full command in a bounded scrolling box.
- Notices whose approval record is not in the loaded list keep the previous folded step.
- The Approvals tab lists pending cards first and recent decisions as one-line rows. The tab
  badge and the session header's "Needs approval" count pending approvals only.

## Contract

No new endpoints. Sources confirmed against manager `origin/main` on 2026-11-12:

- `GET approvals` returns `AgentApproval` with `native_id` (ACP request id), `resource_ref`
  (tool call id), `request_body` (original `session/request_permission` params, whose
  `toolCall.rawInput` holds `command` and `description`), `status` (`pending` → `decided`),
  `decision`, `decision_option`, `decision_scope`, `decided_by_user_id` and `decided_at`.
- Manager injects `approval_id`/`approvalId` into the forwarded permission frame and rewrites
  the stored `session/request_permission` history row with it. Those rows have no text parts
  and were previously dropped; they are now kept as anchors.
- Matching uses the injected approval id when present, otherwise the tool call id
  (`resource_ref`) or the request id (`native_id`). The list is already scoped to the session.

Unit coverage: `ApprovalPresentationTest`.
