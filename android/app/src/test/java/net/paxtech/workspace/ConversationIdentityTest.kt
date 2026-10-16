package net.paxtech.workspace

import net.paxtech.workspace.data.*
import org.junit.Assert.*
import org.junit.Test

class ConversationIdentityTest {
    @Test
    fun decodesSessionTypeAndMatchesItsOwnDevice() {
        val session =
            wireJson.decodeFromString<AgentSession>(
                """{"session_id":"s","node_id":"n","agent_id":"a","agent_type":"codex"}"""
            )
        val identity =
            conversationIdentity(
                session,
                listOf(Node("other", "Wrong device"), Node("n", "Todd’s MacBook", "mac.local")),
            )
        assertEquals(ConversationIdentity("Codex", "Todd’s MacBook"), identity)
    }

    @Test
    fun normalizesClaudeAliasesAndFallsBackToHostnameEvenWhenOffline() {
        listOf("claude", "claude-code", " CLAUDE_CODE ", "claude-agent-acp").forEach { type ->
            assertEquals(
                ConversationIdentity("Claude", "mac.local"),
                conversationIdentity(
                    AgentSession("s", "n", "a", agent_type = type),
                    listOf(Node("n", " ", "mac.local", online = false)),
                ),
            )
        }
    }

    @Test
    fun missingOrUnknownTypesNeverGuessFromIdsOrSessionTitles() {
        val session = AgentSession("s", "n", "codex-agent", name = "Claude discussion")
        assertEquals(
            ConversationIdentity("Assistant", "Unknown device"),
            conversationIdentity(session, emptyList()),
        )
        assertEquals(
            "Custom Harness",
            conversationIdentity(session.copy(agent_type = "Custom Harness"), emptyList()).agentName,
        )
    }
}
