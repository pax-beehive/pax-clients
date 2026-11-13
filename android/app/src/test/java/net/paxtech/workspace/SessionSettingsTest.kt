package net.paxtech.workspace

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.PaxApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SessionSettingsTest {
    // Shape from manager GET .../configuration (see pax-console session-config-selector.test.tsx).
    private val configuration =
        wireJson.decodeFromString(
            SessionConfiguration.serializer(),
            """{"session_id":"sess_1","can_set":true,"can_force_refresh":true,
            "observed_at":"2026-09-05T12:00:00Z","source":"live","options":[
              {"id":"model","name":"Model","category":"model","type":"select","current_value":"opus",
               "options":[{"value":"opus","name":"Opus"},{"value":"sonnet","name":"Sonnet"}]},
              {"id":"reasoning_effort","name":"Thinking","category":"thought_level","type":"select",
               "current_value":"high","options":[{"value":"medium","name":"Medium"},{"value":"high","name":"High"}]},
              {"id":"mode","name":"Mode","category":"mode","type":"select","current_value":"default",
               "options":[{"value":"default","name":"Default"}]},
              {"id":"fast","name":"Fast mode","type":"boolean","current_value":false}
            ],"unknown_future_field":1}""",
        )

    @Test
    fun summaryAndVisibleOptionsMatchTheWebComposer() {
        assertEquals("Opus · High", configuration.summary)
        // Agent "mode" options are permission modes, chosen from the permission catalog instead.
        assertEquals(listOf("model", "reasoning_effort", "fast"), configuration.visibleOptions.map { it.id })
        val fast = configuration.options.last()
        assertTrue(fast.isBoolean)
        assertEquals("Off", fast.valueLabel)
    }

    @Test
    fun legacyModelsStillProduceAModelLabel() {
        val legacy =
            SessionConfiguration(
                legacy_models =
                    SessionLegacyModels("m2", listOf(SessionLegacyModel("m1", "One"), SessionLegacyModel("m2", "Two")))
            )
        assertEquals("Two", legacy.summary)
    }

    @Test
    fun permissionChoicesFollowTheWebCatalogRules() {
        val paxOnly = permissionChoicesFromCatalog(AgentPermissionCatalog(source = "pax_only"))
        assertEquals(listOf(PAX_AUTO_APPROVE_CHOICE_ID, PAX_MANUAL_CHOICE_ID), paxOnly.map { it.choice_id })
        assertEquals(PAX_MANUAL_CHOICE_ID, currentPermissionChoiceId(paxOnly, null, null))
        assertEquals(
            PAX_AUTO_APPROVE_CHOICE_ID,
            currentPermissionChoiceId(paxOnly, SessionPaxConfig(approval_mode = "auto_approve_all"), null),
        )

        val catalog =
            AgentPermissionCatalog(
                default_choice_id = "bypass",
                choices =
                    listOf(
                        AgentPermissionChoice("default", "Default", kind = "agent"),
                        AgentPermissionChoice("bypass", "Bypass", kind = "agent", requires_confirmation = true),
                        AgentPermissionChoice("default", "Duplicate", kind = "agent"),
                    ),
            )
        val choices = permissionChoicesFromCatalog(catalog)
        assertEquals(listOf(PAX_AUTO_APPROVE_CHOICE_ID, "default", "bypass"), choices.map { it.choice_id })
        // A default that needs confirmation is never applied silently.
        assertEquals(PAX_MANUAL_CHOICE_ID, currentPermissionChoiceId(choices, SessionPaxConfig(), catalog))
        assertEquals("default", currentPermissionChoiceId(choices, null, catalog.copy(default_choice_id = "")))
        assertEquals("bypass", currentPermissionChoiceId(choices, SessionPaxConfig(permission_choice_id = "bypass"), catalog))
    }

    @Test
    fun stateSummaryHidesAgentOptionsForEncryptedSessions() {
        val session = AgentSession("s", "n", "a", pax_config = SessionPaxConfig(permission_choice_id = PAX_MANUAL_CHOICE_ID))
        val plain = SessionSettingsState(session, configuration, AgentPermissionCatalog(), loading = false)
        assertEquals("Opus · High · Ask before tools", plain.summary)
        val secure = plain.copy(session = session.copy(transport = "e2ee"))
        assertEquals("Ask before tools", secure.summary)
        assertEquals(JsonPrimitive("high"), configuration.options[1].current_value)
    }

    @Test
    fun requestsUseTheManagerSessionRoutes() = runBlocking {
        MockWebServer().use { server ->
            fun ok(data: String) =
                MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":200,"data":$data}""")
            val repository =
                WorkspaceRepository(
                    PaxApi(),
                    MemoryVault(
                        VaultData(
                            credential =
                                Credential(server.url("/").toString(), "usr", "u@example.invalid", "paxu_k", "key", "us")
                        )
                    ),
                )
            val session = AgentSession("sess_1", "node_1", "agent_1")
            server.enqueue(ok("""{"session_id":"sess_1","options":[],"can_set":true}"""))
            server.enqueue(ok("""{"session_id":"sess_1","options":[],"can_set":true}"""))
            server.enqueue(ok("""{"choices":[],"source":"pax_only"}"""))
            server.enqueue(
                ok("""{"session_id":"sess_1","node_id":"node_1","agent_id":"agent_1","pax_config":{"permission_choice_id":"pax:auto_approve","approval_mode":"auto_approve_all"}}""")
            )

            repository.sessionConfiguration(session)
            repository.setSessionConfigOption(session, "reasoning_effort", JsonPrimitive("high"))
            repository.permissionCatalog("agent_1")
            val saved = repository.setSessionPermission(session, PAX_AUTO_APPROVE_CHOICE_ID)

            val base = "/api/v1/user/self/nodes/node_1/agents/agent_1/sessions/sess_1"
            server.takeRequest().let {
                assertEquals("GET", it.method)
                assertEquals("$base/configuration", it.path)
            }
            server.takeRequest().let {
                assertEquals("PATCH", it.method)
                assertEquals("$base/configuration/options/reasoning_effort", it.path)
                assertEquals("""{"value":"high"}""", it.body.readUtf8())
            }
            server.takeRequest().let {
                assertEquals("GET", it.method)
                assertEquals("/api/v1/user/self/agents/agent_1/permission-catalog", it.path)
            }
            server.takeRequest().let {
                assertEquals("POST", it.method)
                assertEquals("$base/permission", it.path)
                assertEquals("""{"permission_choice_id":"pax:auto_approve"}""", it.body.readUtf8())
            }
            assertEquals("auto_approve_all", saved.pax_config?.approval_mode)
        }
    }
}
