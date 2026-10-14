package net.paxtech.workspace

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.paxtech.workspace.session.ChatItem
import org.junit.*
import org.junit.Assert.*

class SendMotionTest {
    @get:Rule val compose = createComposeRule()
    private val messages =
        mutableStateOf(listOf(ChatItem("older", "old", "agent", "Ready when you are.")))
    private val busy = mutableStateOf(false)
    private val width = mutableStateOf(360.dp)
    private var requests = 0

    @Before
    fun isolatedDeviceOnly() {
        Assume.assumeTrue(
            Build.HARDWARE in listOf("ranchu", "goldfish") &&
                InstrumentationRegistry.getArguments().getString("paxFixtureTests") == "true"
        )
        compose.mainClock.autoAdvance = false
    }

    private fun scene(motion: SendMotionState, accept: Boolean = true) {
        compose.setContent {
            WorkspaceTheme {
                SendMotionHost(
                    motion,
                    Modifier.width(width.value)
                        .height(640.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("motion-scene"),
                ) { state ->
                    Column {
                        ConversationTimeline(
                            messages.value,
                            "Connected",
                            false,
                            false,
                            {},
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            motion = state,
                            waitingForReply = busy.value && messages.value.last().role == "user",
                        )
                        MessageComposer(
                            "Connected",
                            busy.value,
                            null,
                            onSend = { text ->
                                if (!accept) null
                                else {
                                    requests++
                                    val id = "user:prompt_$requests"
                                    messages.value += ChatItem(id, "turn_$requests", "user", text)
                                    busy.value = true
                                    id
                                }
                            },
                            onStop = { busy.value = false },
                            motion = state,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(32)
    }

    @Test
    fun bubbleTravelsFromComposerAndCanonicalAckDoesNotDuplicateIt() {
        val motion = SendMotionState()
        scene(motion)
        compose
            .onNodeWithTag("message-composer")
            .performTextInput("Make the small details feel effortless.")
        compose.mainClock.advanceTimeBy(250)
        // Let the platform keyboard settle; its inset animation is not driven by Compose's clock.
        android.os.SystemClock.sleep(600)
        compose.mainClock.advanceTimeBy(32)
        capture("send-before")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals("Sending must not wait for animation", 1, requests)
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle {
            assertNotNull(motion.flight)
            assertNotNull(motion.flight!!.destination)
            assertTrue(motion.flight!!.destination!!.top < motion.flight!!.origin.top)
        }
        capture("send-lift")
        // A quick server may replace the local ID while the bubble is still moving.
        compose.runOnIdle {
            messages.value =
                messages.value.dropLast(1) + messages.value.last().copy(id = "canonical_user")
        }
        compose.mainClock.advanceTimeBy(112)
        compose.runOnIdle { assertTrue(motion.matches(messages.value.last())) }
        capture("send-midpoint")
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertNull(motion.flight) }
        compose.onAllNodesWithText("Make the small details feel effortless.").assertCountEquals(1)
        compose.onNodeWithContentDescription("Waiting for agent response").assertExists()
        capture("send-landed-dot")
        compose.runOnIdle {
            messages.value +=
                ChatItem(
                    "reply",
                    "turn_1",
                    "agent",
                    "I’ll start with the details you notice every time.",
                )
        }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithContentDescription("Waiting for agent response").assertDoesNotExist()
        compose
            .onNodeWithText("I’ll start with the details you notice every time.")
            .assertIsDisplayed()
        capture("send-first-response")
    }

    @Test
    fun foldDuringFlightSettlesMessageWithoutResending() {
        val motion = SendMotionState()
        scene(motion)
        compose
            .onNodeWithTag("message-composer")
            .performTextInput("Keep this message through a fold.")
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { width.value = 640.dp }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertNull(motion.flight)
            assertEquals(1, requests)
        }
        compose.onAllNodesWithText("Keep this message through a fold.").assertCountEquals(1)
    }

    @Test
    fun disabledMotionStillSendsAndDisplaysImmediately() {
        val motion = SendMotionState(enabled = false)
        scene(motion)
        compose.onNodeWithTag("message-composer").performTextInput("No motion, same delivery.")
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle {
            assertNull(motion.flight)
            assertEquals(1, requests)
        }
        compose.onNodeWithText("No motion, same delivery.").assertExists()
    }

    @Test
    fun rejectedSendKeepsTheDraftInsteadOfAnimatingItAway() {
        val motion = SendMotionState()
        scene(motion, accept = false)
        compose.onNodeWithTag("message-composer").performTextInput("Keep my unsent draft.")
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertNull(motion.flight)
            assertEquals(0, requests)
        }
        compose.onNodeWithTag("message-composer").assertTextContains("Keep my unsent draft.")
    }

    @Test
    fun chineseDraftAndFastReplyRemainVisibleAfterLanding() {
        val motion = SendMotionState()
        scene(motion)
        val prompt = "把输入框里的文字，轻轻送进对话里。\n等待回复时，让圆点呼吸。"
        compose.onNodeWithTag("message-composer").performTextInput(prompt)
        compose.mainClock.advanceTimeBy(250)
        android.os.SystemClock.sleep(600)
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { assertNotNull(motion.flight) }
        compose.mainClock.advanceTimeBy(112)
        capture("send-chinese-midpoint")
        compose.runOnIdle {
            messages.value += ChatItem("quick-reply", "turn_1", "agent", "收到，开始处理。")
            busy.value = false
        }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertNull(motion.flight)
            assertEquals(1, requests)
        }
        compose.onAllNodesWithText(prompt).assertCountEquals(1)
        compose.onNodeWithText("收到，开始处理。").assertIsDisplayed()
        compose.onNodeWithContentDescription("Waiting for agent response").assertDoesNotExist()
        capture("send-chinese-landed")
    }

    @Test
    fun scrollingDraftSettlesWithoutAnimatingHiddenLines() {
        val motion = SendMotionState()
        scene(motion)
        val prompt = (1..30).joinToString("\n") { "Line $it remains in the sent message." }
        compose.onNodeWithTag("message-composer").performTextInput(prompt)
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertNull(motion.flight)
            assertEquals(1, requests)
        }
        compose.onAllNodesWithText(prompt).assertCountEquals(1)
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory =
            File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose
                .onNodeWithTag("motion-scene")
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
