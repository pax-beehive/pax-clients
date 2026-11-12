package net.paxtech.workspace

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.paxtech.workspace.data.Approval
import net.paxtech.workspace.data.ApprovalOption
import net.paxtech.workspace.data.wireJson
import net.paxtech.workspace.session.ApprovalRef
import net.paxtech.workspace.session.ChatItem
import net.paxtech.workspace.session.Timeline
import org.junit.Assert.*
import org.junit.Test

class ApprovalPresentationTest {
    // Mirrors manager's conversationApprovalOptions.
    private val managerOptions =
        listOf(
            ApprovalOption("deny", "Deny", "deny", "once"),
            ApprovalOption("allow_once", "Allow once", "allow", "once"),
            ApprovalOption("allow_for_this_session", "Allow for this session", "allow", "session"),
            ApprovalOption("allow_for_this_agent", "Allow for this agent", "allow", "agent"),
            ApprovalOption("allow_always_on_this_node", "Allow always on this node", "allow", "node"),
            ApprovalOption(
                "allow_always_on_all_agents",
                "Allow always on all agents",
                "allow",
                "across_all_agents",
            ),
        )

    private val prCommand =
        "gh pr create -R pax-beehive/pax-console --base fix/agent-sign-in-status " +
            "--head feat/harness-usage --body-file - <<'EOF'\n## Summary\n\nPlan usage.\nEOF"

    private fun approval(
        id: String = "apr_1",
        status: String = "pending",
        decision: String = "",
        option: String = "",
        scope: String = "",
        body: String? = null,
    ) =
        wireJson.decodeFromString(
            Approval.serializer(),
            """{
              "approval_id":"$id","status":"$status","title":${JsonPrimitive(prCommand)},
              "description":"Open a PR for the harness plan-usage work",
              "native_id":"7","resource_ref":"toolu_pr","decision":"$decision",
              "decision_option":"$option","decision_scope":"$scope",
              "decided_by_user_id":"user_me","decided_at":"2026-11-12T20:31:05.123Z",
              "options":${wireJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ApprovalOption.serializer()), managerOptions)}
              ${body?.let { ""","request_body":$it""" } ?: ""}
            }""",
        )

    @Test
    fun storedPermissionRequestBecomesAnAnchorInsteadOfBeingDropped() {
        val timeline = Timeline()
        timeline.history(
            wireJson
                .parseToJsonElement(
                    """{"message_id":"msg_perm","turn_id":"turn","role":"assistant",
                    "message_type":"session/request_permission",
                    "raw_json":{"jsonrpc":"2.0","id":7,"method":"session/request_permission",
                      "params":{"sessionId":"s","approval_id":"apr_1",
                        "toolCall":{"toolCallId":"toolu_pr","title":"gh pr create"}}},
                    "parts":[{"part_index":0,"part_type":"raw_json"}]}"""
                )
                .jsonObject
        )
        val item = timeline.snapshot().single()
        assertEquals("approval", item.role)
        assertEquals("gh pr create", item.title)
        assertEquals(ApprovalRef("apr_1", "toolu_pr", "7"), item.approval)
    }

    @Test
    fun liveFrameCarriesTheInjectedApprovalId() {
        val timeline = Timeline()
        timeline.frame(
            wireJson
                .parseToJsonElement(
                    """{"jsonrpc":"2.0","id":"req-9","method":"session/request_permission",
                    "params":{"approvalId":"apr_live","toolCall":{"toolCallId":"toolu_x","title":"Edit"}}}"""
                )
                .jsonObject,
            "turn",
        )
        assertEquals(ApprovalRef("apr_live", "toolu_x", "req-9"), timeline.snapshot().single().approval)
    }

    @Test
    fun injectedIdIsAuthoritativeAndOtherIdsAreFallbacks() {
        val record = approval()
        assertTrue(record.matches(ApprovalRef(approvalId = "apr_1")))
        assertFalse(record.matches(ApprovalRef("apr_other", toolCallId = "toolu_pr", requestId = "7")))
        assertTrue(record.matches(ApprovalRef(toolCallId = "toolu_pr")))
        assertTrue(record.matches(ApprovalRef(requestId = "7")))
        assertFalse(record.matches(ApprovalRef()))
        assertFalse(record.matches(null))
    }

    @Test
    fun pendingNoticesHideWhileDecidedOnesCollapseIntoOneSummary() {
        val pending = approval("apr_wait")
        val decided =
            (1..30).map {
                approval("apr_$it", "decided", "allow", "allow_once", "once")
                    .copy(resource_ref = "", native_id = "")
            }
        val notices =
            (1..30).flatMap {
                listOf(
                    ChatItem(
                        "permission:$it",
                        "turn",
                        "approval",
                        "Review this request in Approvals.",
                        "Command $it",
                        approval = ApprovalRef(approvalId = "apr_$it"),
                    ),
                    ChatItem("tool:$it", "turn", "tool", "Output $it"),
                )
            }
        val waiting =
            ChatItem("permission:wait", "turn", "approval", "", approval = ApprovalRef("apr_wait"))
        val answer = ChatItem("answer", "turn", "agent", "Done")
        val visible = withoutPendingNotices(notices + waiting + answer, decided + pending)

        assertFalse(visible.contains(waiting))
        val entries = conversationEntries(visible)
        assertEquals(listOf(60, 1), entries.map { it.messages.size })
        val split = splitDecidedApprovals(entries.first(), decided + pending)
        assertEquals(30, split.decided.size)
        assertEquals(30, split.steps!!.messages.size)
        assertTrue(split.steps!!.messages.all { it.role == "tool" })
    }

    @Test
    fun noticesWithoutAKnownApprovalKeepTheFoldedStep() {
        val entry =
            ConversationEntry(
                listOf(
                    ChatItem("p", "t", "approval", "Review", approval = ApprovalRef("apr_unknown")),
                    ChatItem("plain", "t", "approval", "Review"),
                )
            )
        val split = splitDecidedApprovals(entry, listOf(approval("apr_1", "decided", "allow")))
        assertEquals(entry, split.steps)
        assertTrue(split.decided.isEmpty())
    }

    @Test
    fun onlyDecidedApprovalsLeaveNoActivityBox() {
        val entry =
            ConversationEntry(
                listOf(ChatItem("p", "t", "approval", "", approval = ApprovalRef("apr_1")))
            )
        val split =
            splitDecidedApprovals(entry, listOf(approval("apr_1", "decided", "allow", "allow_once")))
        assertNull(split.steps)
        assertEquals(listOf("apr_1"), split.decided.map { it.approval_id })
    }

    @Test
    fun cardShowsTheAgentsSentenceAndTheCommandFromTheRequest() {
        val body =
            """{"toolCall":{"kind":"execute","title":"gh pr create …",
            "rawInput":{"command":${JsonPrimitive(prCommand)},"description":"Open the PR"}}}"""
        val text = approval(body = body).presentation()
        assertEquals("Open a PR for the harness plan-usage work", text.headline)
        assertEquals(prCommand, text.detail)
        assertEquals("Command", text.kind)
        assertTrue(text.summary.startsWith("gh pr create -R pax-beehive/pax-console"))

        // A JSON-encoded string body is accepted too, and a missing body falls back to the title.
        assertEquals("Command", approval(body = JsonPrimitive(body).toString()).presentation().kind)
        val bare = approval().copy(description = "").presentation()
        assertEquals("Approval requested", bare.headline)
        assertEquals(prCommand, bare.detail)
    }

    @Test
    fun denyAndAllowOnceStayInlineAndBroaderGrantsMoveToMore() {
        val actions = approvalActions(managerOptions)
        assertEquals("deny", actions.deny?.option_id)
        assertEquals("allow_once", actions.allow?.option_id)
        assertEquals(
            listOf(
                "allow_for_this_session",
                "allow_for_this_agent",
                "allow_always_on_this_node",
                "allow_always_on_all_agents",
            ),
            actions.more.map { it.option_id },
        )
        val single = approvalActions(listOf(ApprovalOption("allow_once", "Allow once")))
        assertNull(single.deny)
        assertEquals("allow_once", single.allow?.option_id)
        assertTrue(single.more.isEmpty())
    }

    @Test
    fun outcomeLabelsReadAsPastDecisions() {
        assertEquals("Needs approval", approval().outcomeLabel())
        assertEquals(
            "Allowed once",
            approval(status = "decided", decision = "allow", option = "allow_once").outcomeLabel(),
        )
        assertEquals(
            "Allowed for this session",
            approval(status = "decided", decision = "allow", option = "allow_for_this_session")
                .outcomeLabel(),
        )
        assertEquals(
            "Denied",
            approval(status = "decided", decision = "deny", option = "deny").outcomeLabel(),
        )
        assertEquals(ApprovalTone.Denied, approval(status = "decided", option = "deny").tone)
        assertEquals("Expired", approval(status = "expired").outcomeLabel())
        assertEquals(ApprovalTone.Neutral, approval(status = "expired").tone)
    }

    @Test
    fun decisionTimeIsLocalAndBroadGrantsAreSpelledOut() {
        val zone = ZoneId.of("America/Los_Angeles")
        val sameDay = Instant.parse("2026-11-12T22:00:00Z")
        // Newer CLDR data uses a narrow no-break space before AM/PM.
        fun time(iso: String) = approvalTime(iso, zone, sameDay, Locale.US).replace(' ', ' ')
        assertEquals("12:31 PM", time("2026-11-12T20:31:05.123Z"))
        assertEquals("Nov 10, 9:05 AM", time("2026-11-10T17:05:00Z"))
        assertEquals("12:31 PM", time("2026-11-12T12:31:05-08:00"))
        assertEquals("", time("not a time"))

        val broad =
            approval(
                status = "decided",
                decision = "allow",
                option = "allow_always_on_all_agents",
                scope = "across_all_agents",
            )
        val meta = broad.decisionMeta("user_me", zone)
        assertTrue(meta, meta.startsWith("Decided "))
        assertTrue(meta, meta.contains(" by you"))
        assertTrue(meta, meta.endsWith("Every agent on every device"))
        assertFalse(broad.decisionMeta("someone_else", zone).contains("by you"))
        assertEquals(
            "Claude on MacBook Pro, in every session",
            scopeDescription("agent", "Claude", "MacBook Pro"),
        )
    }
}
