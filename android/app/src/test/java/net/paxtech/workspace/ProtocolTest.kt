package net.paxtech.workspace

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.paxtech.workspace.auth.DeviceLogin
import net.paxtech.workspace.crypto.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.*
import net.paxtech.workspace.session.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class MemoryVault(var value: VaultData = VaultData()) : Vault {
    override suspend fun read() = value

    override suspend fun update(change: (VaultData) -> VaultData): VaultData {
        value = change(value)
        return value
    }
}

class ProtocolTest {
    private fun vector() =
        wireJson
            .parseToJsonElement(javaClass.getResource("/web-vectors.json")!!.readText())
            .jsonObject

    private fun response(data: JsonElement) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                buildJsonObject {
                        put("code", 200)
                        put("data", data)
                    }
                    .toString()
            )

    private fun credential(server: MockWebServer) =
        Credential(
            server.url("/").toString(),
            "usr_fixture",
            "fixture@example.invalid",
            "paxu_fixture",
            "key_fixture",
            "us",
        )

    @Test
    fun decryptsUpstreamWebCiphertextAndRejectsTampering() {
        val fixture = vector()
        val root = decode64(fixture.text("root"))
        val envelope = wireJson.decodeFromJsonElement<Envelope>(fixture.getValue("envelope"))
        assertEquals(
            fixture.text("plaintext"),
            String(EnvelopeCrypto.open(root, "event", envelope)),
        )
        assertThrows(Exception::class.java) { EnvelopeCrypto.open(root, "command", envelope) }
        assertThrows(Exception::class.java) {
            EnvelopeCrypto.open(root, "event", envelope.copy(session_id = "another_session"))
        }
        val damaged = decode64(envelope.payload).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) {
            EnvelopeCrypto.open(root, "event", envelope.copy(payload = encode64(damaged)))
        }
        assertThrows(Exception::class.java) {
            EnvelopeCrypto.open(root, "event", envelope.copy(protocol_version = 2))
        }
        assertThrows(Exception::class.java) {
            wireJson.decodeFromJsonElement<Envelope>(
                JsonObject(fixture.getValue("envelope").jsonObject - "protocol_version")
            )
        }
    }

    @Test
    fun approvalResumesOrdinarySessionAfterDecision() = runBlocking {
        MockWebServer().use { server ->
            val repository =
                WorkspaceRepository(
                    PaxApi(),
                    MemoryVault(VaultData(credential = credential(server))),
                )
            val approval =
                Approval(
                    "approval_fixture",
                    request_node_id = "node_fixture",
                    request_agent_id = "agent_fixture",
                    request_session_id = "session_fixture",
                )
            server.enqueue(response(buildJsonObject {}))
            server.enqueue(response(body("transport" to "plain")))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"type\":\"done\"}\n\n")
            )
            repository.decide(approval, ApprovalOption("allow_once"))
            repository.resumeApproval(approval)
            assertEquals(
                "/api/v1/user/self/approvals/approval_fixture/decision",
                server.takeRequest().path,
            )
            assertEquals(
                "/api/v1/user/self/nodes/node_fixture/agents/agent_fixture/sessions/session_fixture",
                server.takeRequest().path,
            )
            val resumed = server.takeRequest()
            assertEquals(
                "/api/v1/user/self/nodes/node_fixture/agents/agent_fixture/conversation",
                resumed.path,
            )
            val payload = wireJson.parseToJsonElement(resumed.body.readUtf8()).jsonObject
            assertEquals("session_fixture", payload.text("session_id"))
            assertEquals(
                "approval_fixture",
                payload.getValue("resume").jsonObject.text("approval_id"),
            )
        }
    }

    @Test
    fun emitsAndroidCiphertextForIndependentWebVerification() {
        val fixture = vector()
        val output =
            EnvelopeCrypto.seal(
                decode64(fixture.text("root")),
                "command",
                "agent_fixture",
                "session_fixture",
                1,
                "cmd_android",
                "acp_command",
                "Android -> Web 世界".toByteArray(),
            )
        val file = File("build/test-vectors/android-envelope.json")
        file.parentFile!!.mkdirs()
        file.writeText(wireJson.encodeToString(output))
        val second =
            EnvelopeCrypto.seal(
                decode64(fixture.text("root")),
                "command",
                "agent_fixture",
                "session_fixture",
                1,
                "cmd_android_2",
                "acp_command",
                "Android -> Web 世界".toByteArray(),
            )
        assertNotEquals(output.nonce, second.nonce)
    }

    @Test
    fun unwrapsBrowserGeneratedDeviceKeyPackage() = runBlocking {
        MockWebServer().use { server ->
            val fixture = vector()
            val p = wireJson.decodeFromJsonElement<Pairing>(fixture.getValue("pairing"))
            server.enqueue(response(fixture.getValue("package")))
            val vault =
                MemoryVault(VaultData(credential = credential(server), pairings = listOf(p)))
            DevicePairing(WorkspaceRepository(PaxApi(), vault), vault).finish(p)
            assertEquals(fixture.text("root"), vault.value.keys.single().encoded)
            assertTrue(vault.value.pairings.isEmpty())
            assertTrue(server.takeRequest().path!!.contains("key_epoch=1"))
        }
    }

    @Test
    fun pinsLoginIdentityAndSavesBeforeAcknowledgement() = runBlocking {
        MockWebServer().use { server ->
            val c = credential(server)
            val candidate =
                LoginCandidate(c.origin, "us", "login_fixture", "ABC123", "poll_fixture", 2)
            val vault =
                MemoryVault(
                    VaultData(
                        login =
                            LoginAttempt(
                                listOf(candidate),
                                "https://paxworkspace.net/paxl-login.html",
                                Long.MAX_VALUE,
                            )
                    )
                )
            val user = body("user_id" to c.userId, "email" to c.email)
            server.enqueue(
                response(
                    buildJsonObject {
                        put("status", "confirmed")
                        put("region", "us")
                        put("user", user)
                    }
                )
            )
            server.enqueue(
                response(
                    buildJsonObject {
                        put("status", "approved")
                        put("region", "us")
                        put("user", user)
                        put("api_key", c.apiKey)
                        put("api_key_meta", body("key_id" to c.keyId))
                    }
                )
            )
            server.enqueue(response(body("status" to "consumed")))
            val result = DeviceLogin(PaxApi(), vault).finish()
            assertEquals(c, result)
            assertNull(vault.value.login)
            val calls =
                (1..3).map {
                    wireJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                }
            assertEquals(listOf("", "commit", "ack"), calls.map { it.text("action") })
            assertEquals(c.userId, calls[1].text("expected_user_id"))
        }
    }

    @Test
    fun rejectsCredentialFromDifferentIdentity() = runBlocking {
        MockWebServer().use { server ->
            val c = credential(server)
            val candidate =
                LoginCandidate(c.origin, "us", "login_fixture", "ABC123", "poll_fixture", 2)
            val vault =
                MemoryVault(
                    VaultData(
                        login =
                            LoginAttempt(
                                listOf(candidate),
                                "https://paxworkspace.net/paxl-login.html",
                                Long.MAX_VALUE,
                                candidate,
                                c.userId,
                                c.email,
                            )
                    )
                )
            server.enqueue(
                response(
                    buildJsonObject {
                        put("status", "approved")
                        put("region", "us")
                        put("user", body("user_id" to "other", "email" to c.email))
                        put("api_key", "wrong")
                        put("api_key_meta", body("key_id" to "wrong"))
                    }
                )
            )
            try {
                DeviceLogin(PaxApi(), vault).finish()
                fail("Identity mismatch accepted")
            } catch (_: IllegalStateException) {}
            assertNull(vault.value.credential)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun resumesReceiptWithoutIssuingAnotherCredential() = runBlocking {
        MockWebServer().use { server ->
            val c = credential(server)
            val candidate =
                LoginCandidate(c.origin, "us", "login_fixture", "ABC123", "poll_fixture", 2)
            val vault =
                MemoryVault(
                    VaultData(
                        c,
                        LoginAttempt(
                            listOf(candidate),
                            "https://paxworkspace.net/paxl-login.html",
                            Long.MAX_VALUE,
                            candidate,
                            c.userId,
                            c.email,
                        ),
                    )
                )
            server.enqueue(response(body("status" to "consumed")))
            assertEquals(c, DeviceLogin(PaxApi(), vault).finish())
            assertEquals(
                "ack",
                wireJson
                    .parseToJsonElement(server.takeRequest().body.readUtf8())
                    .jsonObject
                    .text("action"),
            )
        }
    }

    @Test
    fun parsesSseUnicodeMultilineAndIgnoresIncompleteEvents() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        ": ping\r\nid: 12\r\ndata: Hello 世界 👋\r\ndata: next\r\n\r\nid: 13\ndata: incomplete"
                    )
                    .throttleBody(3, 1, TimeUnit.MILLISECONDS)
            )
            val api = PaxApi()
            val events =
                api.events(api.request(server.url("/").toString(), listOf("events"))).toList()
            assertEquals(listOf(ServerEvent("12", "Hello 世界 👋\nnext")), events)
        }
    }

    @Test
    fun doesNotFollowRedirectWithCredentials() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                server.enqueue(
                    MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal"))
                )
                val api = PaxApi()
                try {
                    api.json(
                        api.request(server.url("/").toString(), listOf("resource"), "paxu_fixture")
                    )
                    fail("Redirect followed")
                } catch (e: ApiFailure) {
                    assertEquals(302, e.status)
                }
                assertEquals(0, other.requestCount)
            }
        }
    }

    @Test
    fun longStreamingTextKeepsEveryChunkAndStableRows() {
        val timeline = Timeline()
        repeat(5000) {
            timeline.frame(
                buildJsonObject {
                    put("method", "session/update")
                    putJsonObject("params") {
                        putJsonObject("update") {
                            put("sessionUpdate", "agent_message_chunk")
                            putJsonObject("content") { put("text", "字") }
                        }
                    }
                },
                "turn_1",
            )
        }
        assertEquals(1, timeline.snapshot().size)
        assertEquals("字".repeat(5000), timeline.snapshot().single().text)
        val id = timeline.snapshot().single().id
        timeline.frame(
            buildJsonObject {
                put("method", "session/update")
                putJsonObject("params") {
                    putJsonObject("update") {
                        put("sessionUpdate", "agent_message_chunk")
                        putJsonObject("content") { put("text", "!") }
                    }
                }
            },
            "turn_1",
        )
        assertEquals(id, timeline.snapshot().single().id)
    }

    @Test
    fun canonicalTurnReplacesTransientTextWithoutDuplicatingOlderTurns() {
        val timeline = Timeline()
        val older =
            body("message_id" to "old", "turn_id" to "turn_0", "role" to "user") +
                ("parts" to buildJsonArray { add(body("text" to "old message")) })
        timeline.history(JsonObject(older))
        val message = buildJsonObject {
            put("message_id", "new")
            put("turn_id", "turn_1")
            put("role", "agent")
            putJsonArray("parts") { add(body("text" to "answer")) }
        }
        repeat(2) { timeline.replaceTurn("turn_1", listOf(message)) }
        assertEquals(listOf("old", "new"), timeline.snapshot().map { it.id })
    }
}
