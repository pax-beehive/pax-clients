package net.paxtech.workspace

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.PaxApi
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class NewProjectControllerTest {
    private fun controller(
        server: MockWebServer,
        scope: CoroutineScope,
        done: (Project) -> Unit = {},
        refresh: () -> Unit = {},
    ) =
        NewProjectController(
            WorkspaceRepository(
                PaxApi(),
                MemoryVault(
                    VaultData(
                        credential =
                            Credential(
                                server.url("/").toString(),
                                "user_fixture",
                                "test@example.invalid",
                                "fixture",
                                "",
                                "us",
                            )
                    )
                ),
            ),
            scope,
            done,
            refresh,
        )

    @Test
    fun trimsNameReadsProjectEnvelopeAndPreventsDuplicateSubmission() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"data":{"project":{"project_id":"project_1","display_name":"Mobile"}}}"""
                    )
                    .setBodyDelay(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            )
            val created = CompletableDeferred<Project>()
            val c = controller(server, this, { created.complete(it) })
            c.open()
            c.name(" Mobile ")
            c.create()
            c.create()
            c.dismiss()
            assertTrue(c.state.value!!.creating)
            assertEquals("project_1", withTimeout(5000) { created.await() }.project_id)
            assertNull(c.state.value)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/user/self/projects", request.path)
            assertEquals(
                buildJsonObject { put("display_name", "Mobile") },
                wireJson.parseToJsonElement(request.body.readUtf8()),
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun unknownOutcomeRequiresCheckingProjectsInsteadOfRepeatingPost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            var refreshes = 0
            val c = controller(server, this, refresh = { refreshes++ })
            c.open()
            c.name("Mobile")
            c.create()
            withTimeout(5000) { c.state.first { it?.uncertain == true } }
            c.create()
            c.name("Other")
            assertEquals("Mobile", c.state.value!!.name)
            assertEquals(1, server.requestCount)
            c.dismiss()
            assertEquals(1, refreshes)
        }
    }

    @Test
    fun rejectionKeepsDraftEditable() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))
            val c = controller(server, this)
            c.open()
            c.name("Mobile")
            c.create()
            withTimeout(5000) { c.state.first { it?.error != null } }
            assertTrue(c.state.value!!.canCreate)
            c.name("Corrected")
            assertNull(c.state.value!!.error)
        }
    }

    @Test
    fun validatesManagersUtf8ByteLimit() {
        assertFalse(NewProjectState("   ").canCreate)
        assertTrue(NewProjectState("a".repeat(100)).canCreate)
        assertFalse(NewProjectState("a".repeat(101)).canCreate)
        assertTrue(NewProjectState("项".repeat(33)).canCreate)
        assertFalse(NewProjectState("项".repeat(34)).canCreate)
    }
}
