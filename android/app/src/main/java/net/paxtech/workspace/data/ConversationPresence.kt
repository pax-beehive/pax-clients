package net.paxtech.workspace.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/**
 * Manager-reported reachability, independent of session activity and the phone's SSE connection.
 */
data class ConversationPresence(
    val agentOnline: Boolean? = null,
    val deviceOnline: Boolean? = null,
)

suspend fun WorkspaceRepository.conversationPresence(
    nodeId: String,
    agentId: String,
): ConversationPresence = coroutineScope {
    val device = async {
        readPresence {
            val node = get("nodes", nodeId).jsonObject
            if (node.text("node_id") == nodeId) node["online"]?.jsonPrimitive?.booleanOrNull
            else null
        }
    }
    val agent = async {
        readPresence {
            get("nodes", nodeId, "agents")
                .jsonObject
                .array("agents")
                .map { it.jsonObject }
                .firstOrNull { it.text("agent_id") == agentId && it.text("node_id") == nodeId }
                ?.get("online")
                ?.jsonPrimitive
                ?.booleanOrNull
        }
    }
    val deviceOnline = device.await()
    val agentOnline = agent.await()
    // A stale agent heartbeat must not advertise reachability through an unavailable device.
    ConversationPresence(
        agentOnline = if (agentOnline == true && deviceOnline != true) null else agentOnline,
        deviceOnline = deviceOnline,
    )
}

private suspend fun readPresence(read: suspend () -> Boolean?): Boolean? =
    try {
        withTimeoutOrNull(8_000) { read() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/** A cold flow: collection stops with the conversation lifecycle, and resumption starts unknown. */
internal fun observeConversationPresence(
    read: suspend () -> ConversationPresence
): Flow<ConversationPresence> = flow {
    emit(ConversationPresence())
    while (currentCoroutineContext().isActive) {
        emit(read())
        delay(10_000)
    }
}
