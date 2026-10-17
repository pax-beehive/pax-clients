package net.paxtech.workspace

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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
    @Volatile private var gallery = false
    @Volatile private var streaming = false
    @Volatile private var createdSession: JsonObject? = null
    @Volatile private var creationCount = 0
    @Volatile private var initializationCount = 0
    @Volatile private var createdProject: JsonObject? = null
    @Volatile private var projectCreationCount = 0

    @Before
    fun setup() {
        org.junit.Assume.assumeTrue(
            "Fixture tests require the isolated integration emulator",
            Build.HARDWARE in listOf("ranchu", "goldfish") &&
                InstrumentationRegistry.getArguments().getString("paxFixtureTests") == "true",
        )
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
                                "data: {\"type\":\"no_running_turn\",\"session_id\":\"${path.substringBeforeLast("/events").substringAfterLast("/")}\"}\n\n"
                            )
                    if (path.endsWith("/conversation")) {
                        val body = wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                        if (body.flag("initialize_only")) {
                            initializationCount++
                            check(body.text("cwd") == "/workspace/android")
                            check(!body.containsKey("session_id") && !body.containsKey("input"))
                            return MockResponse()
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody(
                                    listOf("session", "done").joinToString("") {
                                        "data: {\"type\":\"$it\",\"session_id\":\"sess_initialized\",\"node_id\":\"node_test\",\"agent_id\":\"agent_test\"}\n\n"
                                    }
                                )
                        }
                        if (
                            createdSession != null &&
                                (initializationCount != 1 ||
                                    body.text("session_id") != "sess_initialized")
                        ) {
                            return MockResponse()
                                .setResponseCode(409)
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """{"code":409,"message":"session has no native ACP session id"}"""
                                )
                        }
                        sent = true
                        if (streaming) {
                            fun chunk(text: String) =
                                """{"type":"acp","turn_id":"turn_live","frame":{"method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","content":{"text":"$text"}}}}}"""
                            val records =
                                listOf(
                                    """{"type":"turn_started","turn_id":"turn_live"}""",
                                    chunk("First visible chunk"),
                                ) +
                                    List(6) { """{"type":"heartbeat"}""" } +
                                    listOf(chunk(" more"), """{"type":"done"}""")
                            return MockResponse()
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody(
                                    records.joinToString("") {
                                        val event = "data: $it\n\n"
                                        event + ":" + " ".repeat(1024 - event.length - 3) + "\n\n"
                                    }
                                )
                                .throttleBody(1024, 300, TimeUnit.MILLISECONDS)
                        }
                        return MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(
                                "data: {\"type\":\"done\",\"session_id\":\"${body.text("session_id")}\"}\n\n"
                            )
                    }
                    val data =
                        when {
                            path.endsWith("/authenticate") -> {
                                val claims =
                                    "{\"sub\":\"user_fixture\",\"exp\":${System.currentTimeMillis() / 1000 + 300}}"
                                val jwt =
                                    "e30." +
                                        Base64.getUrlEncoder()
                                            .withoutPadding()
                                            .encodeToString(claims.toByteArray()) +
                                        ".signature"
                                """{"access_token":"$jwt","refresh_token":"rotated","user":{"id":"user_fixture"}}"""
                            }
                            path.endsWith("/bootstrap") ->
                                """{"status":"ready","user_id":"usr_test","email":"test@example.invalid","region":"us","api_url":"${server.url("/")}"}"""

                            request.method == "POST" && path.endsWith("/projects") -> {
                                projectCreationCount++
                                val name =
                                    wireJson
                                        .parseToJsonElement(request.body.readUtf8())
                                        .jsonObject
                                        .text("display_name")
                                createdProject = buildJsonObject {
                                    put("project_id", "project_mobile")
                                    put("display_name", name)
                                }
                                """{"project":$createdProject}"""
                            }
                            request.method == "POST" && path.endsWith("/sessions") -> {
                                creationCount++
                                createdSession =
                                    wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                                """{"session_id":"${createdSession!!.text("session_id")}","node_id":"node_test","agent_id":"agent_test","agent_type":"codex","name":"${createdSession!!.text("name")}","workspace_roots":["/workspace/android"],"native_id":"native_initialized","runtime_status":"idle"}"""
                            }
                            path.endsWith("/agents") ->
                                """{"agents":[{"node_id":"node_test","agent_id":"agent_test","name":"Codex","online":true}]}"""
                            path.endsWith("/targets") ->
                                """{"targets":[{"target_id":"target_test","agent_id":"agent_test","display_name":"Android workspace","cwd":"/workspace/android","is_default":true,"enabled":true}]}"""
                            gallery && path.endsWith("/sessions") ->
                                """{"sessions":[
                                  {"session_id":"session_test","node_id":"node_test","agent_id":"agent_test","agent_type":"codex","name":"Refine the Android experience","preview":"Reviewing navigation, spacing and the conversation flow.","runtime_status":"idle","primary_project_id":"project_test"},
                                  {"session_id":"session_two","node_id":"node_test","agent_id":"agent_test","agent_type":"codex","name":"Investigate streaming reconnects","preview":"Tracing the connection lifecycle across network changes.","runtime_status":"running","primary_project_id":"project_test"},
                                  {"session_id":"session_three","node_id":"node_test","agent_id":"agent_test","agent_type":"codex","name":"Prepare the next release","transport":"e2ee","runtime_status":"idle"}
                                ]}"""
                            gallery && path.endsWith("/history") ->
                                """{"messages":[
                                  {"message_id":"message_user","session_id":"session_test","turn_id":"turn_test","role":"user","session_seq":1,"parts":[{"part_index":0,"part_type":"text","text":"Review the Android layout and suggest the next steps."}]},
                                  {"message_id":"message_thought","session_id":"session_test","turn_id":"turn_test","role":"assistant","message_type":"thought","session_seq":2,"parts":[{"part_type":"text","text":"I will inspect the layout before changing the message presentation."}]},
                                  {"message_id":"message_tool","session_id":"session_test","turn_id":"turn_test","role":"tool","session_seq":3,"tool":{"title":"Bash · run layout checks","status":"completed"},"parts":[{"part_type":"text","text":"Layout checks passed.\nCompact and expanded windows checked."}]},
                                  {"message_id":"message_tool_2","session_id":"session_test","turn_id":"turn_test","role":"tool","session_seq":4,"tool":{"title":"Read · ConversationUi.kt","status":"completed"},"parts":[{"part_type":"text","text":"Read the message layout and composer implementation."}]},
                                  {"message_id":"message_agent","session_id":"session_test","turn_id":"turn_test","role":"assistant","session_seq":5,"parts":[{"part_index":0,"part_type":"text","text":"The navigation is in place. I checked the session list, conversation view and the expanded layout.\n\nNext, I’ll refine the spacing and make sure your draft stays in place when you fold the phone."}]}
                                ],"pagination":{"has_older":false}}"""
                            path.endsWith("/sessions") ->
                                """{"sessions":[{"session_id":"session_test","node_id":"node_test","agent_id":"agent_test","agent_type":"codex","name":"Android integration session","runtime_status":"idle"}]}"""
                            streaming && sent && path.endsWith("/history") ->
                                """{"messages":[{"message_id":"canonical_user","turn_id":"turn_live","role":"user","session_seq":1,"parts":[{"text":"Show a streamed response"}]},{"message_id":"canonical_agent","turn_id":"turn_live","role":"assistant","session_seq":2,"parts":[{"text":"First visible chunk more"}]}],"pagination":{"has_older":false}}"""
                            path.endsWith("/history") ->
                                """{"messages":[{"message_id":"message_test","session_id":"session_test","turn_id":"turn_test","role":"assistant","session_seq":1,"parts":[{"part_index":0,"part_type":"text","text":"${if(sent) "Instruction received" else "Hello from the test agent"}"}]}],"pagination":{"has_older":false}}"""
                            path.endsWith("/projects") ->
                                """{"projects":[{"project_id":"project_test","display_name":"Android project"}${createdProject?.let { ",$it" }.orEmpty()}]}"""
                            path.endsWith("/nodes") ->
                                """{"nodes":[{"node_id":"node_test","name":"Test workstation","online":true}]}"""
                            path.endsWith("/nodes/node_test") ->
                                """{"node_id":"node_test","name":"Test workstation","online":true}"""
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
        EntryPointAccessors.fromApplication(context, TestAuthEntryPoint::class.java).auth().apply {
            providerOrigin = server.url("/").toString().removeSuffix("/")
            gatewayOrigin = providerOrigin
            apiOrigin = server.url("/").toString()
        }
    }

    @After
    fun cleanup() {
        scenario?.close()
        if (::vault.isInitialized) runBlocking { vault.update { VaultData() } }
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
                                "",
                                "",
                                "us",
                            ),
                        userSession = UserSession("user_fixture", "fixture_refresh"),
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
        // Platform IME/inset animations are outside Compose's idling clock.
        android.os.SystemClock.sleep(600)
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
    fun createsProjectAndKeepsHomePillSelectionAcrossRecreation() {
        launch(true)
        awaitText("Enter workspace")
        compose.onNodeWithText("Enter workspace").performClick()
        awaitText("Android integration session")
        compose.onNodeWithTag("home-sessions").assertIsSelected()
        capture("home-sessions-pills")
        compose.onNodeWithTag("home-projects").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("New session").assertDoesNotExist()
        compose.onNodeWithContentDescription("New project").performClick()
        compose.onNodeWithTag("create-project").assertIsNotEnabled()
        compose.onNodeWithTag("project-name").performTextInput("Mobile workspace")
        scenario!!.recreate()
        awaitText("Mobile workspace")
        capture("new-project-form")
        compose.onNodeWithTag("create-project").performClick()
        compose.waitUntil(10000) { projectCreationCount == 1 }
        awaitText("Mobile workspace")
        compose.onNodeWithTag("home-projects").assertIsSelected()
        compose.onNodeWithTag("project-name").assertDoesNotExist()
        capture("home-projects-created")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithTag("home-projects").assertIsSelected()
        compose.onNodeWithText("Mobile workspace").performClick()
        compose.onNodeWithTag("home-sessions").assertIsSelected()
        compose.onNodeWithContentDescription("New session").performClick()
        awaitText("Mobile workspace")
        compose
            .onNodeWithTag("choose-Project")
            .assertTextContains("Mobile workspace", substring = true)
        assertEquals(1, projectCreationCount)
    }

    @Test
    fun createsSessionFromProjectAndPreservesFormOnRecreation() {
        launch(true)
        awaitText("Enter workspace")
        compose.onNodeWithText("Enter workspace").performClick()
        awaitText("Android integration session")
        compose.onNodeWithContentDescription("New session").performClick()
        awaitText("No project")
        compose.onNodeWithTag("create-session").assertIsNotEnabled()
        compose.onNodeWithTag("choose-Project").performClick()
        compose.onNodeWithText("Android project").performClick()
        awaitText("/workspace/android")
        compose
            .onNodeWithText("Session name (optional)")
            .performScrollTo()
            .performTextInput("Mobile project session")
        scenario!!.recreate()
        awaitText("Mobile project session")
        compose
            .onNodeWithText("Working directory")
            .performScrollTo()
            .assertTextContains("/workspace/android")
        capture("new-session-form")
        compose.onNodeWithTag("create-session").assertIsEnabled().performClick()
        awaitText("Mobile project session")
        compose.waitUntil(10000) { createdSession != null }
        awaitText("Hello from the test agent")
        assertEquals(1, creationCount)
        assertEquals(1, initializationCount)
        assertFalse(sent)
        assertEquals("project_test", createdSession!!.text("primary_project_id"))
        assertEquals("/workspace/android", createdSession!!.obj("pax_config").text("cwd"))
        compose.onNodeWithTag("message-composer").performTextInput("Inspect the new project")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(5000) { sent }
        awaitText("Instruction received")
        capture("new-session-conversation")
    }

    @Test
    fun signedOutAppShowsNativeSignIn() {
        launch(false)
        awaitText("Sign in to PaxWorkspace")
        capture("welcome-signed-out")
        scenario!!.recreate()
        compose.onNodeWithText("Sign in to PaxWorkspace").assertIsDisplayed().performClick()
        awaitText("Sign in")
        compose.onNodeWithText("PaxWorkspace").assertIsDisplayed()
        assertEquals(0, server.requestCount)
        capture("sign-in")
    }

    @Test
    fun sendsImmediatelyStreamsIncrementallyAndKeepsNextDraft() {
        streaming = true
        launch(true)
        awaitText("Enter workspace")
        compose.onNodeWithText("Enter workspace").performClick()
        awaitText("Android integration session")
        compose.onNodeWithText("Android integration session").performClick()
        awaitText("Hello from the test agent")
        compose.onNodeWithTag("message-composer").performTextInput("Show a streamed response")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(1000) {
            compose
                .onNodeWithTag("message-composer")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text
                .isEmpty()
        }
        // This must be a timeline bubble, not merely text still sitting in the composer.
        compose
            .onNode(
                hasText("Show a streamed response") and
                    hasAnyAncestor(hasTestTag("conversation-timeline"))
            )
            .assertIsDisplayed()
        awaitText("First visible chunk")
        compose.onNodeWithText("First visible chunk more").assertDoesNotExist()
        compose.onNodeWithContentDescription("Stop").assertIsEnabled()
        compose.onNodeWithTag("message-composer").performTextInput("Next draft while streaming")
        capture("streaming-partial-with-draft")
        awaitText("First visible chunk more")
        compose.waitUntil(5000) {
            compose.onAllNodesWithContentDescription("Send").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Show a streamed response").assertCountEquals(1)
        compose.onAllNodesWithText("First visible chunk more").assertCountEquals(1)
        compose.onNodeWithTag("message-composer").assertTextContains("Next draft while streaming")
        capture("streaming-reconciled-with-draft")
    }

    @Test
    fun opensSessionSendsInstructionAndReviewsApproval() {
        launch(true)
        awaitText("Enter workspace")
        capture("welcome-signed-in")
        compose.onNodeWithText("Android integration session").assertDoesNotExist()
        compose.onNodeWithText("Enter workspace").performClick()
        awaitText("Android integration session")
        capture("home")
        compose.onNodeWithText("Android integration session").performClick()
        awaitText("Hello from the test agent")
        compose.onNodeWithText("Codex").assertIsDisplayed()
        compose.onNodeWithText("Test workstation").assertIsDisplayed()
        compose.waitUntil(15000) {
            compose
                .onAllNodes(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        "Agent Online",
                    )
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Device Online")
            )
            .assertIsDisplayed()
        compose.onNodeWithText("Agent").assertDoesNotExist()
        compose.onNodeWithContentDescription("Session actions").performClick()
        compose.onNodeWithText("Session details").performClick()
        compose.onNodeWithText("Device").assertIsDisplayed()
        compose
            .onNode(hasText("Test workstation") and hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
        capture("conversation-identity-details")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Message your agent").performTextInput("Inspect this project")
        // Wait for the edit to reach the composition before requesting state saving.
        compose.onNodeWithText("Inspect this project").assertIsDisplayed()
        scenario!!.recreate()
        awaitText("Inspect this project")
        compose.onNodeWithText("Inspect this project").assertIsDisplayed()
        awaitText("Connected")
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
        compose.onNodeWithContentDescription("Send").performClick()
        awaitText("Instruction received")
        capture("session")
        assertTrue(sent)
        closeConversation()
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

    @Test
    fun preservesDraftAndSelectionAcrossWindowResizeAndRecreation() {
        fun resize(expanded: Boolean, sidebarVisible: Boolean = expanded) {
            val dimensions = if (expanded) "2200x2480" else "1080x2400"
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand("wm size $dimensions")
                .use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
                }
            compose.waitUntil(15000) {
                val width =
                    ApplicationProvider.getApplicationContext<Context>()
                        .resources
                        .configuration
                        .screenWidthDp
                if (expanded) width >= 720 else width < 720
            }
            if (scenario != null)
                compose.waitUntil(15000) {
                    runCatching { compose.onNodeWithText("Search sessions").assertIsDisplayed() }
                        .isSuccess == sidebarVisible
                }
        }
        resize(true)
        launch(true)
        awaitText("Enter workspace")
        capture("welcome-signed-in")
        compose.onNodeWithText("Android integration session").assertDoesNotExist()
        compose.onNodeWithText("Enter workspace").performClick()
        awaitText("Android integration session")
        compose.onNodeWithText("Android integration session").performClick()
        awaitText("Hello from the test agent")
        compose.onNodeWithText("Codex").assertIsDisplayed()
        compose.onNodeWithText("Test workstation").assertIsDisplayed()
        compose.onNodeWithText("Agent").assertDoesNotExist()
        compose.onNodeWithContentDescription("Session actions").performClick()
        compose.onNodeWithText("Session details").performClick()
        compose.onNodeWithText("Device").assertIsDisplayed()
        compose
            .onNode(hasText("Test workstation") and hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
        capture("conversation-identity-details")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Message your agent").performTextInput("Keep my draft while folding")
        capture("window-expanded")
        compose.onNodeWithText("Search sessions").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Hide sidebar")
            .assert(hasAnyAncestor(hasTestTag("workspace-list")))
        val refreshBounds =
            compose
                .onNodeWithContentDescription("Refresh workspace")
                .fetchSemanticsNode()
                .boundsInRoot
        val collapseBounds =
            compose.onNodeWithContentDescription("Hide sidebar").fetchSemanticsNode().boundsInRoot
        assertTrue("Refresh must be left of collapse", refreshBounds.right <= collapseBounds.left)
        val recentBounds =
            compose.onNodeWithText("Recent sessions").fetchSemanticsNode().boundsInRoot
        val addBounds =
            compose.onNodeWithContentDescription("New session").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "New session belongs to the recent sessions heading",
            addBounds.top <= recentBounds.center.y && addBounds.bottom >= recentBounds.center.y,
        )
        assertTrue(addBounds.left > recentBounds.right)
        val splitWidth =
            compose.onNodeWithTag("workspace-detail").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithContentDescription("Hide sidebar").performClick()
        compose.onNodeWithText("Search sessions").assertIsNotDisplayed()
        compose.onNodeWithText("Keep my draft while folding").assertIsDisplayed()
        val fullWidth =
            compose.onNodeWithTag("workspace-detail").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Conversation should gain the sidebar's width", fullWidth > splitWidth)
        capture("window-sidebar-collapsed")
        scenario!!.recreate()
        awaitText("Keep my draft while folding")
        compose.onNodeWithContentDescription("Show sidebar").assertIsDisplayed()
        compose.onNodeWithText("Search sessions").assertIsNotDisplayed()
        resize(false)
        awaitText("Keep my draft while folding")
        compose.onNodeWithText("Keep my draft while folding").assertIsDisplayed()
        compose.onNodeWithText("Search sessions").assertIsNotDisplayed()
        capture("window-compact")
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show sidebar").assertDoesNotExist()
        resize(true, sidebarVisible = false)
        awaitText("Keep my draft while folding")
        compose.onNodeWithContentDescription("Show sidebar").performClick()
        compose.onNodeWithText("Search sessions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide sidebar").assertIsDisplayed()
        scenario!!.recreate()
        awaitText("Keep my draft while folding")
        compose.onNodeWithText("Keep my draft while folding").assertIsDisplayed()
        assertFalse(sent)
        capture("window-restored")
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private fun closeConversation() {
        if (compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Back").performClick()
        } else {
            compose.onNodeWithContentDescription("Session actions").performClick()
            compose.onNodeWithText("Close conversation").performClick()
        }
    }

    @Test
    fun polishedNavigationSearchAndLargeTextStayUsable() {
        gallery = true
        shell("wm size 1080x2400")
        try {
            launch(true)
            awaitText("Enter workspace")
            compose.onNodeWithText("Enter workspace").performClick()
            awaitText("Refine the Android experience")
            capture("polish-home-compact")
            compose.onNodeWithText("Search sessions").performTextInput("no such session")
            awaitText("No matching sessions")
            capture("polish-search-empty")
            compose.onNodeWithContentDescription("Clear search").performClick()
            awaitText("Refine the Android experience")
            compose.onNodeWithText("Settings").performClick()
            awaitText("test@example.invalid")
            compose.onNodeWithContentDescription("Refresh workspace").assertDoesNotExist()
            capture("polish-settings")
            compose.onNodeWithText("Devices").performClick()
            awaitText("Test workstation")
            capture("polish-devices")
            compose.onNodeWithContentDescription("Back to settings").performClick()
            compose.onNodeWithText("Home").performClick()
            compose.onNodeWithTag("home-projects").performClick()
            awaitText("Android project")
            compose.onNodeWithText("Android project").performClick()
            awaitText("Refine the Android experience")
            compose.onNodeWithText("Prepare the next release").assertDoesNotExist()
            compose.onNodeWithText("Show all projects").performClick()
            awaitText("Prepare the next release")
            compose.onNodeWithText("Approvals").performClick()
            awaitText("Allow once")
            capture("polish-approval")
            compose.onNodeWithText("Home").performClick()
            compose.onNodeWithText("Refine the Android experience").performClick()
            awaitText("Review the Android layout and suggest the next steps.")
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.onNodeWithContentDescription("Reconnect").assertDoesNotExist()
            compose.onNodeWithContentDescription("Session actions").performClick()
            compose.onNodeWithText("Reconnect session").assertIsDisplayed().performClick()
            awaitText("Connected")
            capture("polish-conversation")
            compose
                .onNodeWithText(
                    "I will inspect the layout before changing the message presentation."
                )
                .assertDoesNotExist()
            compose.onNodeWithTag("activity-group:message_thought").performClick()
            compose.onNodeWithTag("activity-step:message_tool").performClick()
            compose.onNodeWithText("Layout checks passed.", substring = true).assertIsDisplayed()
            capture("polish-activity-details")
            compose.onNodeWithTag("activity-group:message_thought").performClick()
            compose
                .onNodeWithText("Message your agent")
                .performTextInput("Let’s start with the session list.")
            compose.onNodeWithContentDescription("Send").assertIsEnabled()
            capture("polish-composer-keyboard")
            shell("input keyevent 4")
            shell("wm size 2200x2480")
            compose.waitUntil(15000) {
                runCatching { compose.onNodeWithText("Search sessions").assertIsDisplayed() }
                    .isSuccess
            }
            compose.onNodeWithContentDescription("Back").assertDoesNotExist()
            compose.onAllNodesWithContentDescription("Refresh workspace").assertCountEquals(1)
            compose
                .onNode(
                    hasText("Refine the Android experience") and
                        hasAnyAncestor(hasTestTag("workspace-list"))
                )
                .assertIsSelected()
            capture("polish-expanded")
            compose.onNodeWithContentDescription("Session actions").performClick()
            compose.onNodeWithText("Reconnect session").assertIsDisplayed()
            compose.onNodeWithText("Close conversation").assertIsDisplayed()
            capture("polish-session-actions")
            shell("input keyevent 4")
            shell("settings put system font_scale 1.5")
            compose.waitUntil(15000) {
                ApplicationProvider.getApplicationContext<Context>()
                    .resources
                    .configuration
                    .fontScale > 1.4f
            }
            compose.onNodeWithContentDescription("Session actions").assertIsDisplayed()
            compose.onNodeWithText("Search sessions").assertIsDisplayed()
            capture("polish-expanded-large-text")
            shell("wm size 1080x2400")
            compose.waitUntil(15000) {
                runCatching { compose.onNodeWithContentDescription("Back").assertIsDisplayed() }
                    .isSuccess
            }
            awaitText("Let’s start with the session list.")
            compose.onNodeWithContentDescription("Send").assertIsDisplayed().assertIsEnabled()
            capture("polish-large-text")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Sign out of this device").performScrollTo().performClick()
            awaitText("Sign out?")
            compose.onNodeWithText("Cancel").performClick()
            shell("settings put system font_scale 1.0")
            shell("wm size 2200x2480")
            compose.onNodeWithText("Home").performClick()
            awaitText("Your workspace, in focus")
            capture("polish-expanded-empty")
        } finally {
            shell("settings put system font_scale 1.0")
            shell("wm size 2200x2480")
        }
    }
}
