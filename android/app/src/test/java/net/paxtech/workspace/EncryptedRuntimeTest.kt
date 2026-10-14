package net.paxtech.workspace

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.paxtech.workspace.crypto.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.*
import net.paxtech.workspace.session.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class EncryptedRuntimeTest {
    @Test
    fun resumesAtAuthenticatedCursorAndRejectsTamperedNextEvent() = runBlocking {
        val fixture =
            wireJson
                .parseToJsonElement(javaClass.getResource("/web-vectors.json")!!.readText())
                .jsonObject
        MockWebServer().use { server ->
            val root = decode64(fixture.text("root"))
            val frame = buildJsonObject {
                put("method", "session/update")
                putJsonObject("params") {
                    putJsonObject("update") {
                        put("sessionUpdate", "agent_message_chunk")
                        putJsonObject("content") { put("text", " continued") }
                    }
                }
            }
            val plain =
                buildJsonObject {
                        put("turn_id", "turn_fixture")
                        putJsonArray("frames") { add(frame) }
                    }
                    .toString()
                    .toByteArray()
            val live =
                EnvelopeCrypto.seal(
                    root,
                    "event",
                    "agent_fixture",
                    "session_fixture",
                    1,
                    "event_2",
                    "acp_event",
                    plain,
                )
            val tampered = live.copy(record_id = "event_3", payload = encode64(ByteArray(32)))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        buildJsonObject {
                                put("code", 200)
                                putJsonObject("data") {
                                    put("turn_ref", "turn_fixture")
                                    put("turn_start_cursor", 1)
                                    put("head_cursor", 1)
                                    put("next_after_cursor", 1)
                                    put("has_more", false)
                                    put("has_older", false)
                                    putJsonArray("events") {
                                        addJsonObject {
                                            put("cursor", 1)
                                            put("envelope", fixture.getValue("envelope"))
                                        }
                                    }
                                }
                            }
                            .toString()
                    )
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("id: 2\ndata: ${wireJson.encodeToJsonElement(live)}\n\n")
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("id: 3\ndata: ${wireJson.encodeToJsonElement(tampered)}\n\n")
            )
            val credential =
                Credential(
                    server.url("/").toString(),
                    "usr_fixture",
                    "fixture@example.invalid",
                    "paxu_fixture",
                    "key_fixture",
                    "us",
                )
            val vault =
                MemoryVault(
                    VaultData(
                        credential = credential,
                        keys =
                            listOf(
                                RootKey(credential.userId, "agent_fixture", 1, fixture.text("root"))
                            ),
                    )
                )
            val repo = WorkspaceRepository(PaxApi(), vault)
            val runtime = SessionRuntime(repo, DevicePairing(repo, vault))
            withTimeout(10000) {
                runtime.run(
                    AgentSession(
                        "session_fixture",
                        "node_fixture",
                        "agent_fixture",
                        transport = "e2ee",
                    )
                )
            }
            assertEquals("Hello 世界 👋 continued", runtime.state.value.messages.single().text)
            assertNotNull(runtime.state.value.error)
            assertEquals("Unavailable", runtime.state.value.connection)
            assertTrue(server.takeRequest().path!!.contains("view=replay"))
            assertEquals("1", server.takeRequest().getHeader("Last-Event-ID"))
            assertEquals("2", server.takeRequest().getHeader("Last-Event-ID"))
        }
    }

    @Test
    fun missingKeyNeverRequestsPlaintextHistory() = runBlocking {
        MockWebServer().use { server ->
            val c =
                Credential(
                    server.url("/").toString(),
                    "usr_fixture",
                    "fixture@example.invalid",
                    "paxu_fixture",
                    "key_fixture",
                    "us",
                )
            val vault = MemoryVault(VaultData(credential = c))
            val repo = WorkspaceRepository(PaxApi(), vault)
            val runtime = SessionRuntime(repo, DevicePairing(repo, vault))
            runtime.run(
                AgentSession("session_fixture", "node_fixture", "agent_fixture", transport = "e2ee")
            )
            assertTrue(runtime.state.value.locked)
            assertEquals(0, server.requestCount)
        }
    }
}
