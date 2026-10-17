package net.paxtech.workspace

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.*
import net.paxtech.workspace.session.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class AttachmentTest {
    private fun obj(value: String) = wireJson.parseToJsonElement(value).jsonObject

    private fun frame(content: String, kind: String = "agent_message_chunk") =
        obj(
            """{"method":"session/update","params":{"update":{"sessionUpdate":"$kind","toolCallId":"t1","content":$content}}}"""
        )

    private val image = """{"type":"image","mimeType":"image/png","data":"aGVsbG8="}"""

    @Test
    fun imageOnlyMessagesSurviveLiveAndDurableHistoryWithoutDuplicates() {
        val timeline = Timeline()
        timeline.frame(frame(image), "turn")
        assertEquals("image/png", timeline.snapshot().single().attachments.single().mimeType)
        val raw = frame(image)
        timeline.replaceTurn(
            "turn",
            listOf(
                obj(
                    """{"message_id":"m","turn_id":"turn","role":"assistant","raw_json":$raw,"parts":[{"part_index":0,"part_type":"raw_json","payload_json":$raw}]}"""
                )
            ),
        )
        assertEquals(1, timeline.snapshot().size)
        assertEquals(1, timeline.snapshot().single().attachments.size)
        assertEquals("aGVsbG8=", timeline.snapshot().single().attachments.single().base64)
    }

    @Test
    fun mediaSeparatesStreamingTextAndToolUpdatesPreserveAttachments() {
        val timeline = Timeline()
        timeline.frame(frame("""{"type":"text","text":"Before"}"""), "turn")
        timeline.frame(frame(image), "turn")
        timeline.frame(frame("""{"type":"text","text":"After"}"""), "turn")
        assertEquals(listOf("Before", "", "After"), timeline.snapshot().map { it.text })
        timeline.frame(frame("""[{"type":"content","content":$image}]""", "tool_call"), "turn")
        timeline.frame(frame("[]", "tool_call_update"), "turn")
        assertEquals(1, timeline.snapshot().last().attachments.size)
    }

    @Test
    fun parsesPublicationsAndEmbeddedResourcesWithoutFetchingLocalPaths() {
        val timeline = Timeline()
        timeline.history(
            obj(
                """{"message_id":"pub","role":"assistant","parts":[{"part_type":"artifact","artifact_uri":"artifact-publication://apub_test/main","payload_json":{"publication_id":"apub_test"}}]}"""
            )
        )
        assertEquals(
            "artifact-publication://apub_test/main",
            timeline.snapshot().single().attachments.single().uri,
        )
        val marker = buildJsonObject {
            put("type", "text")
            put(
                "text",
                """{"accepted":true,"pax_artifact_publication":{"publication_id":"apub_test"}}""",
            )
        }
        assertEquals("artifact-publication://apub_test/main", acpAttachments(marker).single().uri)
        val resource =
            acpAttachments(
                    obj(
                        """{"type":"resource","resource":{"uri":"file:///home/user/report.txt","mimeType":"text/plain","text":"Report"}}"""
                    )
                )
                .single()
        assertEquals("Report", resource.inlineText)
        assertEquals("report.txt", resource.name)
        assertNull(attachmentHttpsUrl("file:///home/user/report.txt"))
        assertNull(attachmentHttpsUrl("https://user:secret@example.invalid/file"))
        assertNull(attachmentRoute("artifact-publication://id/.."))
        assertNull(attachmentRoute("artifact-publication://id/%2fsecret"))
        assertEquals("image.png", attachmentName("../image.png"))
        val localized = "att_" + "a".repeat(48)
        assertEquals(
            AttachmentRoute("attachments", localized),
            attachmentRoute("file:///tmp/$localized/report.pdf"),
        )
        assertNull(attachmentRoute("file:///home/private/report.pdf"))
        assertTrue(
            acpAttachments(obj("""{"type":"resource","resource":{"blob":{},"text":[]}}"""))
                .single()
                .base64 == null
        )
    }

    private fun repo(server: MockWebServer, api: PaxApi = PaxApi()) =
        WorkspaceRepository(
            api,
            MemoryVault(
                VaultData(
                    credential =
                        Credential(
                            server.url("/").toString(),
                            "user",
                            "test@example.invalid",
                            "private-token",
                            "",
                            "us",
                        )
                )
            ),
        )

    private val target = AgentSession("sess_1", "node_1", "agent_1")
    private val publication =
        ChatAttachment("Published file", uri = "artifact-publication://apub_1/main")

    private fun ticket(
        status: String = "available",
        session: String = "sess_1",
        url: String = "https://files.example.invalid/a",
    ) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"data":{"status":"$status","publication":{"publication_id":"apub_1","session_id":"$session","agent_id":"agent_1","node_id":"node_1"},"content":{"filename":"chart.png","content_type":"image/png","size_bytes":3},"url":"$url"}}"""
            )

    @Test
    fun resolvesOnlyReadyFilesFromTheMatchingSessionAndDoesNotReadRemoteDiskPaths() = runBlocking {
        MockWebServer().use { server ->
            val repository = repo(server)
            for ((status, session, expected) in
                listOf(
                    Triple("not_available", "sess_1", "uploading"),
                    Triple("available", "sess_other", "belong"),
                )) {
                server.enqueue(ticket(status, session))
                try {
                    repository.attachmentContent(target, publication)
                    fail("Must not download")
                } catch (e: IllegalStateException) {
                    assertTrue(e.message!!.contains(expected))
                }
            }
            val count = server.requestCount
            try {
                repository.attachmentContent(
                    target,
                    ChatAttachment("private", uri = "file:///etc/private"),
                )
                fail("Must not fetch a file path")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("publish_artifact"))
            }
            assertEquals(count, server.requestCount)
            val inline =
                repository.attachmentContent(
                    target.copy(transport = "e2ee"),
                    ChatAttachment("image", "image/png", base64 = "aGVsbG8="),
                )
            assertEquals("hello", inline.bytes.toString(Charsets.UTF_8))
            assertEquals(count, server.requestCount)
        }
    }

    @Test
    fun signedDownloadsNeverCarryCredentialsAndRejectRedirectsOrOversizedBodies() = runBlocking {
        val cert =
            HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls =
            HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val client =
            OkHttpClient.Builder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        val api = PaxApi(client)
        MockWebServer().use { manager ->
            MockWebServer().use { storage ->
                storage.useHttps(serverTls.sslSocketFactory(), false)
                storage.enqueue(
                    MockResponse().setHeader("Content-Type", "image/png").setBody("png")
                )
                manager.enqueue(ticket(url = storage.url("/signed").toString()))
                val loaded = repo(manager, api).attachmentContent(target, publication)
                assertEquals("chart.png", loaded.name)
                assertEquals("png", loaded.bytes.toString(Charsets.UTF_8))
                assertEquals(
                    "Bearer private-token",
                    manager.takeRequest().getHeader("Authorization"),
                )
                assertNull(storage.takeRequest().getHeader("Authorization"))
                storage.enqueue(
                    MockResponse().setResponseCode(302).setHeader("Location", storage.url("/never"))
                )
                try {
                    api.downloadAttachment(storage.url("/redirect").toString(), 10)
                    fail("No redirects")
                } catch (e: ApiFailure) {
                    assertEquals(302, e.status)
                }
                storage.enqueue(MockResponse().setChunkedBody("too much data", 2))
                try {
                    api.downloadAttachment(storage.url("/large").toString(), 4)
                    fail("Enforce streaming limit")
                } catch (e: IllegalArgumentException) {
                    assertTrue(e.message!!.contains("limit"))
                }
                assertEquals(3, storage.requestCount)
            }
        }
    }
}
