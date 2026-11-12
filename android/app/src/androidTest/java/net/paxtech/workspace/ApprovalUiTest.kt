package net.paxtech.workspace

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.paxtech.workspace.data.Approval
import net.paxtech.workspace.data.ApprovalOption
import net.paxtech.workspace.data.wireJson
import net.paxtech.workspace.session.ApprovalRef
import net.paxtech.workspace.session.ChatItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ApprovalUiTest {
    @get:Rule val compose = createComposeRule()

    private val command =
        "gh pr create -R pax-beehive/pax-console --base fix/agent-sign-in-status " +
            "--head feat/harness-usage --title \"feat: show native harness plan usage in agent " +
            "sign-in\" --body-file - <<'EOF'\n## Summary\n\nWhen sign-in credentials are " +
            "detected, Agent sign-in now shows the device's **Plan usage** at full width below " +
            "the status row.\n\n- **Windows**: one compact row per window.\n" +
            (1..30).joinToString("\n") { "- detail line $it" } +
            "\nEOF"

    private val options =
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

    private fun approval(id: String, decided: Boolean) =
        Approval(
            approval_id = id,
            title = command,
            description = "Open a PR for the harness plan-usage work",
            status = if (decided) "decided" else "pending",
            request_session_id = "session",
            options = options,
            request_body =
                wireJson
                    .parseToJsonElement(
                        """{"toolCall":{"kind":"execute","title":${JsonPrimitive(command)},
                        "rawInput":{"command":${JsonPrimitive(command)}}}}"""
                    )
                    .jsonObject,
            decision = if (decided) "allow" else "",
            decision_option = if (decided) "allow_once" else "",
            decision_scope = if (decided) "once" else "",
            decided_by_user_id = if (decided) "user_me" else "",
            decided_at = if (decided) "2026-11-12T20:31:05Z" else "",
        )

    private val transcript =
        listOf(
            ChatItem("user:1", "turn", "user", "把 feat/harness-usage 开个 PR"),
            ChatItem("agent:1", "turn", "agent", "Pushed the branch. Opening the PR now."),
            ChatItem("tool:push", "turn", "tool", "", "git push -u origin feat/harness-usage", "completed"),
            ChatItem(
                "permission:7",
                "turn",
                "approval",
                "Review this request in Approvals.",
                "gh pr create",
                approval = ApprovalRef(approvalId = "apr_pr"),
            ),
        )

    private fun show(messages: List<ChatItem>, approvals: List<Approval>) {
        compose.setContent {
            WorkspaceTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ConversationTimeline(
                        messages,
                        "Connected",
                        false,
                        false,
                        {},
                        Modifier.width(412.dp).height(860.dp),
                        approvals = approvals,
                        approval = {
                            PendingApprovalCard(
                                it,
                                false,
                                ConversationIdentity("Claude", "MacBook Pro"),
                            ) {}
                        },
                        identity = ConversationIdentity("Claude", "MacBook Pro"),
                        viewerId = "user_me",
                    )
                }
            }
        }
    }

    @Test
    fun pendingApprovalIsCompactAndKeepsBroadGrantsBehindMore() {
        show(transcript, listOf(approval("apr_pr", decided = false)))

        compose.onNodeWithText("Needs approval").assertIsDisplayed()
        compose.onNodeWithText("Open a PR for the harness plan-usage work").assertIsDisplayed()
        compose.onNodeWithText("Deny").assertIsDisplayed()
        compose.onNodeWithText("Allow once").assertIsDisplayed()
        compose.onNodeWithText("Show full command · 37 lines").assertIsDisplayed()
        compose.onNodeWithText("Allow always on all agents").assertDoesNotExist()
        // The notice is not shown a second time while the card is pending.
        compose.onNodeWithText("Review this request in Approvals.").assertDoesNotExist()
        val card = compose.onNodeWithTag("approval-card:apr_pr").fetchSemanticsNode()
        assertTrue("card height ${card.size.height}", card.size.height < card.size.width * 1.2f)
        capture("pending")

        compose.onNodeWithContentDescription("More approval options").performClick()
        compose.waitUntil(3000) {
            compose.onAllNodesWithText("Allow always on all agents").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Every agent on every device").assertIsDisplayed()
        compose.onNodeWithText("Claude on MacBook Pro, in every session").assertIsDisplayed()
        compose.waitForIdle()
        capture("more", window = true)
        compose.onNodeWithText("Allow for this session").performClick()
        compose.onNodeWithText("Confirm").assertIsDisplayed()
        compose.onNodeWithText("Until this session ends").assertIsDisplayed()
    }

    @Test
    fun decidedApprovalCollapsesToOneLineInPlace() {
        val answer = ChatItem("agent:2", "turn", "agent", "Opened the PR.")
        show(transcript + answer, listOf(approval("apr_pr", decided = true)))

        compose.onNodeWithTag("approval-card:apr_pr").assertDoesNotExist()
        compose.onNodeWithText("Allowed once").assertIsDisplayed()
        compose.onNodeWithText("Opened the PR.").assertIsDisplayed()
        compose.onNodeWithText("Activity · 1 step").assertIsDisplayed()
        val row = compose.onNodeWithTag("approval-row:apr_pr").fetchSemanticsNode()
        assertTrue("row height ${row.size.height}", row.size.height < row.size.width / 4)
        capture("decided")

        compose.onNodeWithTag("approval-row:apr_pr").onChildren().onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Open a PR for the harness plan-usage work").assertIsDisplayed()
        capture("decided-open")
    }

    @Test
    fun manyDecidedApprovalsShareOneSummaryLine() {
        val messages =
            (1..30).flatMap {
                listOf(
                    ChatItem("tool:$it", "turn", "tool", "", "Command $it", "completed"),
                    ChatItem(
                        "permission:$it",
                        "turn",
                        "approval",
                        "Review this request in Approvals.",
                        "Command $it",
                        approval = ApprovalRef(approvalId = "apr_$it"),
                    ),
                )
            } + ChatItem("answer", "turn", "agent", "All checks pass.")
        show(messages, (1..30).map { approval("apr_$it", decided = true) })

        compose.onNodeWithText("Activity · 30 steps").assertIsDisplayed()
        compose.onNodeWithText("30 approvals").assertIsDisplayed()
        compose.onNodeWithText("All checks pass.").assertIsDisplayed()
        capture("many")
    }

    private fun capture(name: String, window: Boolean = false) {
        val bitmap: Bitmap =
            if (window) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            else compose.onRoot().captureToImage().asAndroidBitmap()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder =
            File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
        File(folder, "approval-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
