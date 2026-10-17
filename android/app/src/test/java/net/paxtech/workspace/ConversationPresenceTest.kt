package net.paxtech.workspace

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.PaxApi
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ConversationPresenceTest {
    @Test
    fun matchesBothIdsAndNeverTreatsMissingFailedOrStaleReportsAsOnline() = runBlocking {
        MockWebServer().use { server ->
            var node = """{"node_id":"n","online":true}"""
            var agents =
                """{"agents":[{"node_id":"other","agent_id":"a","online":false},{"node_id":"n","agent_id":"a","online":true}]}"""
            var agentStatus = 200
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        assertEquals("GET", request.method)
                        val agentRequest = request.path == "/api/v1/user/self/nodes/n/agents"
                        if (!agentRequest) assertEquals("/api/v1/user/self/nodes/n", request.path)
                        return MockResponse()
                            .setResponseCode(if (agentRequest) agentStatus else 200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"data\":${if (agentRequest) agents else node}}")
                    }
                }
            val repo =
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
            assertEquals(ConversationPresence(true, true), repo.conversationPresence("n", "a"))
            node = """{"node_id":"n","online":false}"""
            assertEquals(ConversationPresence(null, false), repo.conversationPresence("n", "a"))
            node = """{"node_id":"n","online":true}"""
            agents = """{"agents":[{"node_id":"n","agent_id":"a","online":false}]}"""
            assertEquals(ConversationPresence(false, true), repo.conversationPresence("n", "a"))
            agents = """{"agents":[{"node_id":"n","agent_id":"a"}]}"""
            assertEquals(ConversationPresence(null, true), repo.conversationPresence("n", "a"))
            agents = """{"agents":[{"node_id":"other","agent_id":"a","online":true}]}"""
            assertEquals(ConversationPresence(null, true), repo.conversationPresence("n", "a"))
            agentStatus = 503
            assertEquals(ConversationPresence(null, true), repo.conversationPresence("n", "a"))
            node = """{"node_id":"wrong","online":true}"""
            assertEquals(ConversationPresence(), repo.conversationPresence("n", "a"))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pollingRefreshesAndCancellationStopsRequestsAndResumptionStartsUnknown() = runTest {
        var reads = 0
        var current = ConversationPresence(true, true)
        val values = mutableListOf<ConversationPresence>()
        val flow = observeConversationPresence {
            reads++
            current
        }
        var job = launch { flow.collect { values += it } }
        runCurrent()
        assertEquals(listOf(ConversationPresence(), current), values)
        current = ConversationPresence(false, true)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(current, values.last())
        job.cancelAndJoin()
        advanceTimeBy(30_000)
        assertEquals(2, reads)
        values.clear()
        job = launch { flow.collect { values += it } }
        runCurrent()
        assertEquals(ConversationPresence(), values.first())
        assertEquals(current, values.last())
        job.cancelAndJoin()
    }
}
