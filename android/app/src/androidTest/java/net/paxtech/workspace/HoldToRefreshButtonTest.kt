package net.paxtech.workspace

import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.assertEquals

class HoldToRefreshButtonTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun isolatedDeviceOnly() {
        Assume.assumeTrue(
            Build.HARDWARE in listOf("ranchu", "goldfish") &&
                InstrumentationRegistry.getArguments().getString("paxFixtureTests") == "true"
        )
    }

    @Test
    fun tapAndDragAwayDoNotRefreshButHoldingFiresOnce() {
        var refreshes = 0
        compose.setContent { WorkspaceTheme { HoldToRefreshButton(true) { refreshes++ } } }
        val button = compose.onNodeWithContentDescription("Refresh workspace")
        button.performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, refreshes) }
        button.performTouchInput {
            down(center)
            moveTo(Offset(-100f, -100f), delayMillis = 100)
            advanceEventTime(1000)
            up()
        }
        compose.runOnIdle { assertEquals(0, refreshes) }
        button.performTouchInput { longClick(durationMillis = 1500) }
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test
    fun loadingPreventsLongPressRefresh() {
        var refreshes = 0
        compose.setContent { WorkspaceTheme { HoldToRefreshButton(false) { refreshes++ } } }
        compose
            .onNodeWithContentDescription("Refresh workspace")
            .assertIsNotEnabled()
            .performTouchInput { longClick(durationMillis = 1500) }
        compose.runOnIdle { assertEquals(0, refreshes) }
    }

    @Test
    fun completedRefreshBrieflyConfirmsSuccessButFailuresNeverDo() {
        compose.mainClock.autoAdvance = false
        var enabled by mutableStateOf(true)
        var failed by mutableStateOf(false)
        compose.setContent {
            WorkspaceTheme { HoldToRefreshButton(enabled, failed) { enabled = false } }
        }
        compose.mainClock.advanceTimeBy(32)
        val button = compose.onNodeWithContentDescription("Refresh workspace")
        button.performTouchInput { longClick(durationMillis = 1500) }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { enabled = true }
        compose.mainClock.advanceTimeBy(200)
        compose
            .onNodeWithContentDescription("Workspace refreshed", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1200)
        compose
            .onNodeWithContentDescription("Workspace refreshed", useUnmergedTree = true)
            .assertDoesNotExist()
        button.performTouchInput { longClick(durationMillis = 1500) }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle {
            failed = true
            enabled = true
        }
        compose.mainClock.advanceTimeBy(200)
        compose
            .onNodeWithContentDescription("Workspace refreshed", useUnmergedTree = true)
            .assertDoesNotExist()
    }
}
