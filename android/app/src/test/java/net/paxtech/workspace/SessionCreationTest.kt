package net.paxtech.workspace

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.PaxApi
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class SessionCreationTest {
    private fun response(data: String, status: Int = 200) =
        MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"code\":$status,\"data\":$data}")

    private fun repo(server: MockWebServer) =
        WorkspaceRepository(
            PaxApi(),
            MemoryVault(
                VaultData(
                    credential =
                        Credential(
                            server.url("/").toString(),
                            "usr_fixture",
                            "test@example.invalid",
                            "fixture",
                            "",
                            "us",
                        )
                )
            ),
        )

    private fun options(server: MockWebServer, mutation: (RecordedRequest) -> MockResponse) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path!!.endsWith("/projects") ->
                            response(
                                """{"projects":[{"project_id":"project_1","display_name":"Project"}]}"""
                            )
                        request.path!!.endsWith("/nodes") ->
                            response(
                                """{"nodes":[{"node_id":"node_1","online":true},{"node_id":"node_offline","online":false}]}"""
                            )
                        request.path!!.endsWith("/agents") ->
                            response(
                                """{"agents":[{"agent_id":"agent_1","name":"Codex","online":true}]}"""
                            )
                        request.path!!.endsWith("/targets") ->
                            response(
                                """{"targets":[{"target_id":"disabled","agent_id":"agent_1","enabled":false,"cwd":"/wrong"},{"target_id":"target_1","agent_id":"agent_1","enabled":true,"is_default":true,"cwd":"/workspace"}]}"""
                            )
                        else -> mutation(request)
                    }
            }
    }

    private fun initialized(id: String = "sess_server", done: Boolean = true): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"type\":\"session\",\"session_id\":\"$id\",\"node_id\":\"node_1\",\"agent_id\":\"agent_1\"}\n\n" +
                    if (done)
                        "data: {\"type\":\"done\",\"session_id\":\"$id\",\"node_id\":\"node_1\",\"agent_id\":\"agent_1\"}\n\n"
                    else ""
            )

    private val readySession =
        """{"session_id":"sess_server","node_id":"node_1","agent_id":"agent_1","native_id":"native_7","primary_project_id":"project_1","pax_config":{"cwd":"/workspace"}}"""

    @Test
    fun initializesAgentBeforeConfiguringReturnedIdentity() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(initialized())
            server.enqueue(response(readySession))
            var assigned = ""
            val created =
                repo(server).createSession(
                    SessionDraft("", "project_1", "node_1", "agent_1", " /workspace ", " Test ")
                ) {
                    assigned = it
                }
            assertEquals("sess_server", assigned)
            assertEquals("native_7", created.native_id)
            val initialization = server.takeRequest()
            assertTrue(initialization.path!!.endsWith("/conversation"))
            val initBody = wireJson.parseToJsonElement(initialization.body.readUtf8()).jsonObject
            assertTrue(initBody.flag("initialize_only"))
            assertEquals("/workspace", initBody.text("cwd"))
            assertEquals("project_1", initBody.text("primary_project_id"))
            assertFalse(initBody.containsKey("session_id"))
            assertFalse(initBody.containsKey("input"))
            val configure = server.takeRequest()
            assertTrue(configure.path!!.endsWith("/sessions"))
            val body = wireJson.parseToJsonElement(configure.body.readUtf8()).jsonObject
            assertEquals("sess_server", body.text("session_id"))
            assertEquals("Test", body.text("name"))
            assertFalse(body.containsKey("native_id"))
            assertFalse(body.containsKey("transport"))
            assertFalse(body.obj("pax_config").containsKey("approval_mode"))
        }
    }

    @Test
    fun defaultsToEnabledProjectTargetAndPreventsDuplicateCreation() = runBlocking {
        MockWebServer().use { server ->
            var initializations = 0
            var configurations = 0
            options(server) { request ->
                if (request.path!!.endsWith("/conversation")) {
                    initializations++
                    initialized().setBodyDelay(150, java.util.concurrent.TimeUnit.MILLISECONDS)
                } else {
                    configurations++
                    response(readySession)
                }
            }
            val done = CompletableDeferred<AgentSession>()
            val controller = NewSessionController(repo(server), this) { done.complete(it) }
            controller.open("project_1")
            val loaded = withTimeout(5000) { controller.state.first { it?.canCreate == true } }!!
            assertEquals("target_1", loaded.targetId)
            assertEquals("/workspace", loaded.draft.cwd)
            assertEquals(1, loaded.targets.size)
            controller.create()
            controller.create()
            controller.dismiss()
            assertTrue(controller.state.value!!.creating)
            withTimeout(5000) { done.await() }
            assertEquals(1, initializations)
            assertEquals(1, configurations)
            assertNull(controller.state.value)
        }
    }

    @Test
    fun lostCompletionChecksAssignedIdentityWithoutRepeatingInitialization() = runBlocking {
        MockWebServer().use { server ->
            var posts = 0
            options(server) { request ->
                if (request.method == "POST") {
                    posts++
                    initialized(done = false)
                } else {
                    assertTrue(request.path!!.endsWith("/sess_server"))
                    response(readySession)
                }
            }
            val done = CompletableDeferred<AgentSession>()
            val controller = NewSessionController(repo(server), this) { done.complete(it) }
            controller.open("project_1")
            withTimeout(5000) { controller.state.first { it?.canCreate == true } }
            controller.create()
            withTimeout(5000) { controller.state.first { it?.uncertain == true } }
            controller.cwd("/different")
            assertEquals("/workspace", controller.state.value!!.draft.cwd)
            controller.create()
            assertEquals("sess_server", withTimeout(5000) { done.await() }.session_id)
            assertEquals(1, posts)
        }
    }

    @Test
    fun missingAssignedIdentityNeverRepeatsUnknownMutation() = runBlocking {
        MockWebServer().use { server ->
            var posts = 0
            options(server) {
                posts++
                response("{}", 503)
            }
            val controller =
                NewSessionController(repo(server), this) { error("Unexpected success") }
            controller.open("project_1")
            withTimeout(5000) { controller.state.first { it?.canCreate == true } }
            controller.create()
            withTimeout(5000) { controller.state.first { it?.uncertain == true } }
            controller.create()
            withTimeout(5000) { controller.state.first { it?.creating == false } }
            assertTrue(controller.state.value!!.error!!.contains("Refresh Sessions"))
            assertEquals(1, posts)
        }
    }

    @Test
    fun recoveryRejectsRecordWithoutNativeSessionButPreservesEncryptedTransport() = runBlocking {
        MockWebServer().use { server ->
            val draft = SessionDraft("sess_server", "project_1", "node_1", "agent_1", "/workspace")
            server.enqueue(response(readySession.replace("native_7", "")))
            try {
                repo(server).findCreatedSession(draft)
                fail("Record-only sessions must not open as ready")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("initializing"))
            }
            server.enqueue(
                response(
                    readySession
                        .replace("native_7", "")
                        .replaceFirst("{", "{\"transport\":\"e2ee\",")
                )
            )
            assertTrue(repo(server).findCreatedSession(draft).encrypted)
        }
    }

    @Test
    fun configurationFailureAfterInitializationCannotCreateAnotherSession() = runBlocking {
        MockWebServer().use { server ->
            var initializations = 0
            options(server) { request ->
                when {
                    request.path!!.endsWith("/conversation") -> {
                        initializations++
                        initialized()
                    }
                    request.method == "POST" -> response("{}", 409)
                    else -> response(readySession)
                }
            }
            val done = CompletableDeferred<AgentSession>()
            val controller = NewSessionController(repo(server), this) { done.complete(it) }
            controller.open("project_1")
            withTimeout(5000) { controller.state.first { it?.canCreate == true } }
            controller.create()
            withTimeout(5000) { controller.state.first { it?.uncertain == true } }
            controller.create()
            withTimeout(5000) { done.await() }
            assertEquals(1, initializations)
        }
    }

    @Test
    fun recoveryDoesNotOpenPartiallyConfiguredSession() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                response(
                    """{"session_id":"sess_1","node_id":"node_1","agent_id":"agent_1","pax_config":{}}"""
                )
            )
            try {
                repo(server)
                    .findCreatedSession(
                        SessionDraft(
                            "sess_1",
                            nodeId = "node_1",
                            agentId = "agent_1",
                            cwd = "/workspace",
                        )
                    )
                fail("Partial creation must not open with a fallback directory")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("working directory"))
            }
            assertEquals("GET", server.takeRequest().method)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun offlineSelectionAndRelativePathsCannotCreate() = runBlocking {
        MockWebServer().use { server ->
            options(server) { error("Unexpected mutation") }
            val controller =
                NewSessionController(repo(server), this) { error("Unexpected creation") }
            controller.open()
            withTimeout(5000) { controller.state.first { it?.loading == false } }
            controller.cwd("~/project")
            assertFalse(controller.state.value!!.canCreate)
            controller.cwd("/project")
            assertTrue(controller.state.value!!.canCreate)
            controller.node("node_offline")
            assertFalse(controller.state.value!!.canCreate)
            assertEquals("", controller.state.value!!.draft.cwd)
            controller.dismiss()
        }
    }

    @Test
    fun validatesUnixAndWindowsPathsWithoutExpandingTilde() {
        listOf("/workspace/a b", "C:\\work", "D:/code", "\\\\server\\share").forEach {
            assertTrue(SessionDraft("id", cwd = it).validPath)
        }
        listOf("", "relative", "~/code", "/tmp\nother", "/tmp\u0000").forEach {
            assertFalse(SessionDraft("id", cwd = it).validPath)
        }
    }
}
