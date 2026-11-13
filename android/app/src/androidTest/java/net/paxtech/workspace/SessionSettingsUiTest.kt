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
import net.paxtech.workspace.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    private val configuration =
        wireJson.decodeFromString(
            SessionConfiguration.serializer(),
            """{"session_id":"sess_1","can_set":true,"can_force_refresh":true,"options":[
              {"id":"model","name":"Model","category":"model","type":"select","current_value":"opus",
               "options":[{"value":"opus","name":"Opus","description":"Most capable"},
                          {"value":"sonnet","name":"Sonnet","description":"Faster, cheaper"},
                          {"value":"haiku","name":"Haiku","description":"Quick tasks"}]},
              {"id":"reasoning_effort","name":"Reasoning effort","category":"thought_level","type":"select",
               "current_value":"high","options":[{"value":"low","name":"Low"},{"value":"medium","name":"Medium"},
                 {"value":"high","name":"High"},{"value":"max","name":"Max"}]},
              {"id":"mode","name":"Mode","category":"mode","type":"select","current_value":"default","options":[]}
            ]}""",
        )

    private val catalog =
        AgentPermissionCatalog(
            source = "live",
            choices =
                listOf(
                    AgentPermissionChoice(
                        "default",
                        "Ask before tools",
                        "Every command and edit needs your OK",
                        "agent",
                    ),
                    AgentPermissionChoice(
                        "acceptEdits",
                        "Accept edits",
                        "File edits run; commands still ask",
                        "agent",
                    ),
                    AgentPermissionChoice(
                        "bypassPermissions",
                        "Bypass permissions",
                        "Everything runs without asking",
                        "agent",
                        risk = "high",
                        requires_confirmation = true,
                    ),
                ),
        )

    private val state =
        SessionSettingsState(
            AgentSession(
                "sess_1",
                "node_1",
                "agent_1",
                pax_config = SessionPaxConfig(permission_choice_id = "default"),
            ),
            configuration,
            catalog,
            loading = false,
        )

    @Test
    fun composerShowsOneSettingsButtonWithTheCurrentSummary() {
        compose.setContent {
            WorkspaceTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.width(412.dp).height(300.dp), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                        MessageComposer(
                            connection = "Connected",
                            busy = false,
                            error = null,
                            onSend = { null },
                            onStop = {},
                            settings = { SessionSettingsButton(state.summary, {}) },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Opus · High · Ask before tools").assertIsDisplayed()
        compose.onNodeWithTag("message-composer").assertIsDisplayed()
        capture("composer")
    }

    @Test
    fun sheetChangesOptionsAndConfirmsRiskyPermissions() {
        val options = mutableListOf<Pair<String, JsonPrimitive>>()
        val permissions = mutableListOf<String>()
        compose.setContent {
            WorkspaceTheme {
                SessionSettingsSheet(
                    state,
                    onOption = { id, value -> options += id to value },
                    onPermission = { permissions += it },
                    onRefresh = {},
                    onRetry = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(3000) {
            compose.onAllNodesWithText("Session settings").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Opus").assertIsDisplayed()
        // The agent's "mode" option is not shown twice; permissions come from the catalog.
        compose.onNodeWithText("MODE").assertDoesNotExist()
        compose.waitForIdle()
        capture("sheet", window = true)

        compose.onNodeWithText("Medium").performClick()
        compose.onNodeWithTag("option:model:sonnet").performClick()
        assertEquals(
            listOf("reasoning_effort" to JsonPrimitive("medium"), "model" to JsonPrimitive("sonnet")),
            options,
        )

        compose.onNodeWithTag("session-settings-sheet").performScrollToNode(hasTestTag("permission:bypassPermissions"))
        compose.onNodeWithTag("permission:acceptEdits").performClick()
        assertEquals(listOf("acceptEdits"), permissions)
        compose.onNodeWithTag("permission:bypassPermissions").performClick()
        // The confirmation dialog appears; nothing is applied until Confirm.
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        assertEquals(listOf("acceptEdits"), permissions)
        compose.onNodeWithText("Confirm").performClick()
        assertEquals(listOf("acceptEdits", "bypassPermissions"), permissions)
    }

    private fun capture(name: String, window: Boolean = false) {
        val bitmap: Bitmap =
            if (window) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            else compose.onRoot().captureToImage().asAndroidBitmap()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder =
            File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
        File(folder, "settings-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
