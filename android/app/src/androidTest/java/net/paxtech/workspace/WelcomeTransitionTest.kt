package net.paxtech.workspace

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*
import org.junit.Assert.*

class WelcomeTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun isolatedDeviceOnly() {
        Assume.assumeTrue(
            Build.HARDWARE in listOf("ranchu", "goldfish") &&
                InstrumentationRegistry.getArguments().getString("paxFixtureTests") == "true"
        )
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun signedInApproachesBeforeEnteringOnlyOnce() {
        val entries = mutableIntStateOf(0)
        compose.setContent {
            WorkspaceTheme {
                if (entries.intValue == 0) WelcomeScreen(true, false) { entries.intValue++ }
                else Text("Workspace destination")
            }
        }
        compose.mainClock.advanceTimeBy(2500)
        compose.onNodeWithText("Enter workspace").performTouchInput { doubleClick() }
        compose.mainClock.advanceTimeBy(480)
        assertEquals(0, entries.intValue)
        compose.onNodeWithText("Enter workspace").assertIsNotEnabled()
        capture("zoom-midpoint")
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Workspace destination").assertIsDisplayed()
        assertEquals(1, entries.intValue)
    }

    @Test
    fun signedOutEntersLoginWithoutWaitingForZoom() {
        var entries = 0
        compose.setContent { WorkspaceTheme { WelcomeScreen(false, false) { entries++ } } }
        compose.mainClock.advanceTimeBy(2500)
        compose.onNodeWithText("Sign in to PaxWorkspace").performClick()
        assertEquals(1, entries)
    }

    @Test
    fun pendingEntrySurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val entries = mutableIntStateOf(0)
        restoration.setContent {
            WorkspaceTheme {
                if (entries.intValue == 0) WelcomeScreen(true, false) { entries.intValue++ }
                else Text("Workspace destination")
            }
        }
        compose.mainClock.advanceTimeBy(2500)
        compose.onNodeWithText("Enter workspace").performClick()
        compose.mainClock.advanceTimeBy(250)
        assertEquals(0, entries.intValue)
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithText("Workspace destination").assertIsDisplayed()
        assertEquals(1, entries.intValue)
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder =
            File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
