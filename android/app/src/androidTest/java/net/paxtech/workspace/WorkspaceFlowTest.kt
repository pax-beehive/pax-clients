package net.paxtech.workspace

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import net.paxtech.workspace.data.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

class WorkspaceFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var vault: SecureVault
    private var scenario: ActivityScenario<MainActivity>? = null
    private val server = MockWebServer()
    @Volatile private var sent = false
    @Volatile private var decision = false

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        vault = SecureVault(context)
        runBlocking { vault.update { VaultData() } }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path.endsWith("/events"))
                        return MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(
                                "data: {\"type\":\"no_running_turn\",\"session_id\":\"session_test\"}\n\n"
                            )
                    if (path.endsWith("/conversation")) {
                        sent = true
                        return MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(
                                "data: {\"type\":\"done\",\"session_id\":\"session_test\"}\n\n"
                            )
                    }
                    val data =
                        when {
                            path.endsWith("/sessions") ->
                                """{"sessions":[{"session_id":"session_test","node_id":"node_test","agent_id":"agent_test","name":"Android integration session","runtime_status":"idle"}]}"""
                            path.endsWith("/history") ->
                                """{"messages":[{"message_id":"message_test","session_id":"session_test","turn_id":"turn_test","role":"assistant","session_seq":1,"parts":[{"part_index":0,"part_type":"text","text":"${if(sent) "Instruction received" else "Hello from the test agent"}"}]}],"pagination":{"has_older":false}}"""
                            path.endsWith("/projects") ->
                                """{"projects":[{"project_id":"project_test","display_name":"Android project"}]}"""
                            path.endsWith("/nodes") ->
                                """{"nodes":[{"node_id":"node_test","name":"Test workstation","online":true}]}"""
                            path.endsWith("/approvals") ->
                                if (decision) """{"approvals":[]}"""
                                else
                                    """{"approvals":[{"approval_id":"approval_test","title":"Read project files","status":"pending","options":[{"option_id":"allow_once","label":"Allow once"}]}]}"""
                            path.endsWith("/decision") -> {
                                decision = true
                                "{}"
                            }
                            else -> "{}"
                        }
                    return MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"code\":200,\"data\":$data}")
                }
            }
        server.start()
    }

    @After
    fun cleanup() {
        scenario?.close()
        runBlocking { vault.update { VaultData() } }
        server.shutdown()
    }

    private fun launch(signedIn: Boolean) {
        if (signedIn)
            runBlocking {
                vault.update {
                    VaultData(
                        credential =
                            Credential(
                                server.url("/").toString(),
                                "usr_test",
                                "test@example.invalid",
                                "paxu_test",
                                "key_test",
                                "us",
                            )
                    )
                }
            }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun awaitText(text: String) {
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir =
            File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
        File(dir, name + ".png").outputStream().use {
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun signedOutAppShowsNativeSignIn() {
        launch(false)
        awaitText("Sign in")
        compose.onNodeWithText("PaxWorkspace").assertIsDisplayed()
        assertEquals(0, server.requestCount)
        capture("sign-in")
    }

    @Test
    fun opensSessionSendsInstructionAndReviewsApproval() {
        launch(true)
        awaitText("Android integration session")
        capture("home")
        compose.onNodeWithText("Android integration session").performClick()
        awaitText("Hello from the test agent")
        compose.onNodeWithText("Message your agent").performTextInput("Inspect this project")
        compose.onNodeWithText("Send").performClick()
        awaitText("Instruction received")
        capture("session")
        assertTrue(sent)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Approvals").performClick()
        awaitText("Allow once")
        compose.onNodeWithText("Allow once").performClick()
        compose.onNodeWithText("Confirm").performClick()
        awaitText("No pending approvals.")
        assertTrue(decision)
        scenario!!.recreate()
        awaitText("No pending approvals.")
        compose.onNodeWithText("Home").performClick()
        awaitText("Android integration session")
    }
}
