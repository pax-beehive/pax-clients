package net.paxtech.workspace

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import net.paxtech.workspace.session.ChatItem
import org.junit.*
import org.junit.Assert.*

class ConversationUiTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun isolatedDeviceOnly() {
        Assume.assumeTrue(
            Build.HARDWARE in listOf("ranchu", "goldfish") &&
                InstrumentationRegistry.getArguments().getString("paxFixtureTests") == "true"
        )
    }

    @Test
    fun growingAnswerKeepsTailVisibleAndNeverSuddenlyCollapses() {
        val messages = mutableStateOf(listOf(message("live", "A short answer")))
        val height = mutableStateOf(480.dp)
        lateinit var list: LazyListState
        compose.setContent {
            WorkspaceTheme {
                list = rememberLazyListState()
                ConversationTimeline(
                    messages.value,
                    "Connected",
                    false,
                    false,
                    {},
                    Modifier.width(360.dp).height(height.value),
                    list = list,
                )
            }
        }
        // Cross the old 12,000-character collapse threshold and shrink the viewport like the IME.
        val long =
            (1..300).joinToString("\n") {
                "Streaming paragraph $it with enough text to wrap on a narrow phone."
            }
        compose.runOnIdle { messages.value = listOf(message("live", long)) }
        // Markdown soft line breaks become spaces; wait for the background parse before layout
        // checks.
        val rendered = long.replace("\n", " ")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(rendered).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Expand").assertDoesNotExist()
        compose.runOnIdle { height.value = 280.dp }
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
            assertFalse(list.canScrollBackward)
        }
        compose.runOnIdle { messages.value = messages.value + message("next", "The next message") }
        compose.onNodeWithText("The next message").assertIsDisplayed()
    }

    @Test
    fun readingEarlierMessagesIsNotInterruptedByStreamingOrPagination() {
        val messages =
            mutableStateOf((0..24).map { message("m$it", "Earlier message $it\n".repeat(6)) })
        lateinit var list: LazyListState
        compose.setContent {
            WorkspaceTheme {
                list = rememberLazyListState()
                ConversationTimeline(
                    messages.value,
                    "Connected",
                    true,
                    false,
                    {},
                    Modifier.width(360.dp).height(480.dp),
                    list = list,
                )
            }
        }
        compose.onNodeWithTag("conversation-timeline").performScrollToIndex(10)
        var key: Any? = null
        var offset = 0
        compose.runOnIdle {
            key = list.layoutInfo.visibleItemsInfo.first().key
            offset = list.firstVisibleItemScrollOffset
            messages.value =
                messages.value.dropLast(1) +
                    message("m24", "More live output\n".repeat(100)) +
                    message("new", "New response")
        }
        compose.runOnIdle {
            assertEquals(key, list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
            messages.value = listOf(message("older", "Fetched history")) + messages.value
        }
        compose.runOnIdle {
            assertEquals(key, list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun typingDoesNotRecomposeTimelineAndFailedSendRestoresDraft() {
        var parentCompositions = 0
        var sent = ""
        val connection = mutableStateOf("Connected")
        compose.setContent {
            WorkspaceTheme {
                SideEffect { parentCompositions++ }
                Column(Modifier.width(360.dp)) {
                    ConversationTimeline(
                        listOf(message("old", "Existing conversation")),
                        "Connected",
                        false,
                        false,
                        {},
                        Modifier.height(240.dp),
                    )
                    MessageComposer(
                        connection.value,
                        false,
                        null,
                        {
                            sent = it
                            "user:test"
                        },
                        {},
                    )
                }
            }
        }
        var baseline = 0
        compose.runOnIdle { baseline = parentCompositions }
        compose.onNode(hasSetTextAction()).performTextInput("A draft while the agent responds")
        compose.runOnIdle { assertEquals(baseline, parentCompositions) }
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            assertEquals("A draft while the agent responds", sent)
            connection.value = "Needs reconnect"
        }
        compose.onNode(hasSetTextAction()).assertTextContains(sent)
    }

    private fun message(id: String, text: String) = ChatItem(id, "turn", "agent", text)

    @Test
    fun activityIsCollapsedButFailuresAndFullDetailsRemainAccessible() {
        val messages =
            mutableStateOf(
                listOf(
                    ChatItem("thought", "turn", "thought", "Private fixture reasoning"),
                    ChatItem(
                        "tool",
                        "turn",
                        "tool",
                        "Full bash output\n".repeat(100),
                        "Bash · run checks",
                        "failed",
                    ),
                    message("answer", "The checks found an error."),
                )
            )
        compose.setContent {
            WorkspaceTheme {
                ConversationTimeline(
                    messages.value,
                    "Connected",
                    false,
                    false,
                    {},
                    Modifier.width(360.dp).height(600.dp),
                )
            }
        }
        compose.onNodeWithText("Failed").assertIsDisplayed()
        compose.onNodeWithText("Private fixture reasoning").assertDoesNotExist()
        compose.onNodeWithText("Full bash output", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("activity-group:thought").performClick()
        compose.onNodeWithTag("activity-step:thought").performClick()
        compose.onNodeWithText("Private fixture reasoning").assertIsDisplayed()
        compose.onNodeWithTag("activity-step:tool").performClick()
        compose.onNodeWithText("Full bash output\n".repeat(100)).assertExists()
        compose.runOnIdle {
            messages.value =
                messages.value.map {
                    if (it.id == "tool") it.copy(text = "Updated full output") else it
                }
        }
        compose.onNodeWithText("Updated full output").assertExists()
        compose.onNodeWithTag("activity-group:thought").performClick()
        compose.onNodeWithText("Updated full output").assertDoesNotExist()
        compose.onNodeWithText("The checks found an error.").assertIsDisplayed()
    }
}
