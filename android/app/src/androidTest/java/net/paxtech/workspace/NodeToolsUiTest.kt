package net.paxtech.workspace

import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import net.paxtech.workspace.data.*
import org.junit.*
import org.junit.Assert.*

class NodeToolsUiTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun isolatedDeviceOnly() {
        Assume.assumeTrue(
            Build.HARDWARE in listOf("ranchu", "goldfish") &&
                InstrumentationRegistry.getArguments().getString("paxFixtureTests") == "true"
        )
    }

    @Test
    fun browseOnlyLoadsOpenedFolderAndSelectsIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val calls = mutableListOf<String>()
        var chosen = ""
        val controller =
            DirectoryBrowserController(
                scope,
                { _, path, _, _ ->
                    calls += path
                    if (path.isBlank())
                        DirectoryPage(
                            "/home/demo",
                            "/home/demo",
                            entries = listOf(DirectoryEntry("Projects", "/home/demo/Projects")),
                        )
                    else DirectoryPage("/home/demo", path, parent = "/home/demo")
                },
                { _, path -> chosen = path },
            )
        compose.setContent {
            WorkspaceTheme {
                val state by controller.state.collectAsState()
                LaunchedEffect(Unit) { controller.open("node_fixture") }
                state?.let { DirectoryBrowserDialog(it, controller) }
            }
        }
        compose.onNodeWithText("Up").assertIsNotEnabled()
        compose.onNodeWithText("Projects").performClick()
        compose.onNodeWithText("No subfolders").assertExists()
        compose.onNodeWithText("Select this folder").performClick()
        compose.runOnIdle {
            assertEquals(listOf("", "/home/demo/Projects"), calls)
            assertEquals("/home/demo/Projects", chosen)
            scope.cancel()
        }
    }
}
