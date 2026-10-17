package net.paxtech.workspace

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class WorkspaceMotionTest {
    private var scale = 1f
    @get:Rule
    val compose =
        createComposeRule(
            effectContext =
                object : MotionDurationScale {
                    override val scaleFactor
                        get() = scale
                }
        )
    private val measuredWidths = mutableSetOf<Int>()

    @Before
    fun setup() {
        Assume.assumeTrue(
            Build.HARDWARE in listOf("ranchu", "goldfish") &&
                InstrumentationRegistry.getArguments().getString("paxFixtureTests") == "true"
        )
        compose.mainClock.autoAdvance = false
    }

    private fun scene() {
        compose.setContent {
            WorkspaceTheme {
                AdaptiveWorkspace(
                    true,
                    home = { _, _, hide ->
                        Column {
                            Text("Recent sessions")
                            Button(onClick = { hide?.invoke() }) { Text("Collapse") }
                        }
                    },
                    detail = { _, show ->
                        Column(
                            Modifier.fillMaxSize().onSizeChanged { measuredWidths += it.width }
                        ) {
                            var draft by rememberSaveable { mutableStateOf("Keep this draft") }
                            if (show != null) Button(onClick = show) { Text("Restore") }
                            TextField(draft, { draft = it })
                        }
                    },
                )
            }
        }
        compose.mainClock.advanceTimeBy(32)
    }

    private fun left() =
        compose.onNodeWithTag("workspace-detail").fetchSemanticsNode().boundsInRoot.left

    @Test
    fun sidebarHasIntermediateFramesAndReversalPreservesDraftWithoutContinuousReflow() {
        scene()
        val expandedLeft = left()
        assertTrue(expandedLeft > 0)
        capture("motion-expanded")
        compose.onNodeWithText("Collapse").performClick()
        compose.mainClock.advanceTimeBy(112)
        val middle = left()
        assertTrue(
            "Sidebar should move through an intermediate position",
            middle > 0 && middle < expandedLeft,
        )
        capture("motion-midpoint")
        compose.onNodeWithText("Restore").performClick()
        compose.mainClock.advanceTimeBy(320)
        assertEquals(expandedLeft, left(), 1f)
        compose.onNodeWithText("Keep this draft").assertIsDisplayed()
        compose.onNodeWithText("Collapse").performClick()
        compose.mainClock.advanceTimeBy(320)
        assertEquals(0f, left(), 1f)
        compose.onNodeWithText("Keep this draft").assertIsDisplayed()
        assertEquals("Only the full and split widths should be measured", 2, measuredWidths.size)
        capture("motion-collapsed")
    }

    @Test
    fun zeroDurationScaleCompletesSidebarImmediately() {
        scale = 0f
        scene()
        compose.onNodeWithText("Collapse").performClick()
        compose.mainClock.advanceTimeBy(32)
        assertEquals(0f, left(), 1f)
        compose.onNodeWithText("Restore").assertIsDisplayed()
        compose.onNodeWithText("Keep this draft").assertIsDisplayed()
    }

    @Test
    fun workIndicatorSettlesIntoApprovalAndDisappearsWhenIdle() {
        var working by mutableStateOf(true)
        var approval by mutableStateOf(false)
        compose.setContent { WorkspaceTheme { AgentWorkIndicator(working, approval) } }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithText("Working").assertIsDisplayed()
        compose.runOnIdle { approval = true }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithText("Needs approval").assertIsDisplayed()
        compose.onNodeWithText("Working").assertDoesNotExist()
        compose.runOnIdle {
            working = false
            approval = false
        }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag("agent-work-status").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder =
            File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
