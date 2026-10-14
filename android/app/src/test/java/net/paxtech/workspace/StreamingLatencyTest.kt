package net.paxtech.workspace

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.paxtech.workspace.crypto.DevicePairing
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.PaxApi
import net.paxtech.workspace.session.SessionRuntime
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class StreamingLatencyTest {
    @Test
    fun promptIsImmediateAndPostChunksRenderBeforeDurableHistory() = runBlocking {
        fixture { server, runtime, idle ->
            withTimeout(5000) { idle.await() }
            delay(100)
            val start = System.nanoTime()
            val sender = launch { runtime.send("hello") }
            try {
                val prompt =
                    withTimeout(5000) {
                        runtime.state.first { it.messages.any { m -> m.role == "user" } }
                    }
                val promptMs = elapsed(start)
                val first =
                    withTimeout(5000) {
                        runtime.state.first {
                            it.messages.any { m -> m.role in setOf("agent", "assistant") }
                        }
                    }
                val firstMs = elapsed(start)
                println("Prompt visible: ${promptMs}ms; first response visible: ${firstMs}ms")
                assertTrue("Local echo waited ${promptMs}ms for the server", promptMs < 200)
                assertEquals("hello", prompt.messages.first { it.role == "user" }.text)
                assertEquals("First", first.messages.first { it.role == "agent" }.text)
                assertTrue("First chunk must render while POST is still streaming", sender.isActive)
                val second =
                    withTimeout(2000) {
                        runtime.state.first { it.messages.any { m -> m.text == "First second" } }
                    }
                assertTrue(sender.isActive)
                assertTrue(second.busy)
                assertEquals(1, second.messages.count { it.role == "user" })
                sender.join()
                delay(200) // The observer resumes after history reconciliation.
                val final = runtime.state.value
                assertFalse(final.busy)
                assertNull(final.error)
                assertEquals(listOf("hello", "First second"), final.messages.map { it.text })
                assertEquals(
                    listOf("canonical_user", "canonical_agent"),
                    final.messages.map { it.id },
                )
                assertEquals(
                    1,
                    generateSequence { server.takeRequest(10, TimeUnit.MILLISECONDS) }
                        .count { it.method == "POST" },
                )
            } finally {
                sender.cancelAndJoin()
            }
        }
    }

    @Test
    fun brokenPostKeepsPartialOutputAndUnconfirmedPromptWithoutResending() = runBlocking {
        fixture(complete = false) { server, runtime, idle ->
            withTimeout(5000) { idle.await() }
            runtime.send("hello")
            delay(300)
            val state = runtime.state.value
            assertEquals("Needs reconnect", state.connection)
            assertFalse(state.busy)
            assertNotNull(state.error)
            assertEquals("Delivery unconfirmed", state.messages.first { it.role == "user" }.status)
            assertEquals("First second", state.messages.first { it.role == "agent" }.text)
            assertEquals(
                1,
                generateSequence { server.takeRequest(10, TimeUnit.MILLISECONDS) }
                    .count { it.method == "POST" },
            )
        }
    }

    private suspend fun fixture(
        complete: Boolean = true,
        check:
            suspend CoroutineScope.(
                MockWebServer, SessionRuntime, CompletableDeferred<Unit>,
            ) -> Unit,
    ) = coroutineScope {
        MockWebServer().use { server ->
            val accepted = AtomicBoolean(false)
            val idle = CompletableDeferred<Unit>()
            val canonical =
                """[{"message_id":"canonical_user","turn_id":"turn_test","role":"user","session_seq":1,"parts":[{"text":"hello"}]},{"message_id":"canonical_agent","turn_id":"turn_test","role":"assistant","session_seq":2,"parts":[{"text":"First second"}]}]"""
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.requestUrl!!.encodedPath
                        if (path.endsWith("/history"))
                            return MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """{"code":200,"data":{"messages":${if(accepted.get()) canonical else "[]"},"pagination":{}}}"""
                                )
                        if (path.endsWith("/conversation")) {
                            accepted.set(true)
                            val records =
                                mutableListOf(
                                    """{"type":"turn_started","turn_id":"turn_test"}""",
                                    chunk("First"),
                                    chunk(" second"),
                                    // A prompt echo on the raw stream must not duplicate the local
                                    // bubble.
                                    chunk("hello", "user_message_chunk"),
                                    """{"type":"heartbeat"}""",
                                )
                            if (complete) records += """{"type":"done"}"""
                            return MockResponse()
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody(records.joinToString("") { paddedEvent(it) })
                                .throttleBody(1024, 200, TimeUnit.MILLISECONDS)
                        }
                        // The real observer returns durable history batches, not raw token deltas.
                        idle.complete(Unit)
                        return MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody("data: {\"type\":\"no_running_turn\"}\n\n")
                    }
                }
            val vault =
                MemoryVault(
                    VaultData(
                        credential =
                            Credential(
                                server.url("/").toString(),
                                "usr_test",
                                "test@example.invalid",
                                "paxu_fixture",
                                "key_test",
                                "us",
                            )
                    )
                )
            val repo = WorkspaceRepository(PaxApi(), vault)
            val runtime = SessionRuntime(repo, DevicePairing(repo, vault))
            val observer = launch {
                runtime.run(AgentSession("session_test", "node_test", "agent_test"))
            }
            try {
                check(server, runtime, idle)
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

    private fun chunk(text: String, kind: String = "agent_message_chunk") =
        """{"type":"acp","turn_id":"turn_test","frame":{"method":"session/update","params":{"update":{"sessionUpdate":"$kind","content":{"text":"$text"}}}}}"""

    private fun paddedEvent(json: String): String {
        val event = "data: $json\n\n"
        return event + ":" + " ".repeat(1024 - event.length - 3) + "\n\n"
    }

    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000
}
