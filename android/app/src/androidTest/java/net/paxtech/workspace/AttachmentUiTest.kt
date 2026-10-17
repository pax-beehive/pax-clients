package net.paxtech.workspace

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import net.paxtech.workspace.data.AttachmentContent
import net.paxtech.workspace.session.*
import org.junit.*
import org.junit.Assert.*

class AttachmentUiTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun isolated() {
        Assume.assumeTrue(
            Build.HARDWARE in listOf("ranchu", "goldfish") &&
                InstrumentationRegistry.getArguments().getString("paxFixtureTests") == "true"
        )
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(70, 140, 190))
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    @Test
    fun imageOnlyReplyLoadsOnRequestAndOpensFullPreview() {
        var loads = 0
        val bytes = png()
        val attachment = ChatAttachment("Chart.png", "image/png", base64 = "fixture")
        compose.setContent {
            WorkspaceTheme {
                CompositionLocalProvider(
                    LocalAttachmentLoader provides
                        {
                            loads++
                            AttachmentContent("Chart.png", "image/png", bytes)
                        }
                ) {
                    ConversationTimeline(
                        listOf(
                            ChatItem("image", "turn", "agent", "", attachments = listOf(attachment))
                        ),
                        "Connected",
                        false,
                        false,
                        {},
                        Modifier.width(360.dp).height(680.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("Chart.png").assertIsDisplayed()
        assertEquals(0, loads)
        compose.onNodeWithText("Load image").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("View full image").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, loads)
        compose.waitUntil(5000) {
            compose
                .onAllNodes(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        "Image loaded",
                    )
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        capture("image-card")
        compose.onNodeWithText("View full image").performClick()
        compose.onNodeWithText("Reset zoom").assertIsDisplayed().performClick()
        capture("image-preview", true)
        compose.onNodeWithText("Close preview").performClick()
        compose.onNodeWithText("Save file").assertIsDisplayed()
        compose.onNodeWithText("Open with…").assertIsDisplayed()
    }

    @Test
    fun publicationVisibleWithActivityCollapsedAndCanRetryPendingUpload() {
        var loads = 0
        val attachment = ChatAttachment("Report.txt", uri = "artifact-publication://apub_test/main")
        compose.setContent {
            WorkspaceTheme {
                CompositionLocalProvider(
                    LocalAttachmentLoader provides
                        {
                            loads++
                            if (loads == 1)
                                error("The agent is still uploading this file. Try again shortly.")
                            AttachmentContent(
                                "Report.txt",
                                "text/plain",
                                "Ready report".toByteArray(),
                            )
                        }
                ) {
                    ConversationTimeline(
                        listOf(
                            ChatItem(
                                "tool",
                                "turn",
                                "tool",
                                "internal details",
                                title = "Publish report",
                                attachments = listOf(attachment),
                            )
                        ),
                        "Connected",
                        false,
                        false,
                        {},
                        Modifier.width(360.dp).height(680.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("internal details").assertDoesNotExist()
        compose.onNodeWithText("Report.txt").assertIsDisplayed()
        compose.onNodeWithText("Load file").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Ready report").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Save file").assertIsDisplayed()
        capture("file-card")
        assertEquals(2, loads)
    }

    private fun capture(name: String, dialog: Boolean = false) {
        val bitmap =
            if (dialog) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            else compose.onRoot().captureToImage().asAndroidBitmap()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder =
            File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
        File(folder, "media-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
