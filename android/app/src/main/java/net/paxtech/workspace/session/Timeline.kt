package net.paxtech.workspace.session

import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*

data class ChatItem(
    val id: String,
    val turn: String,
    val role: String,
    val text: String,
    val title: String = "",
    val status: String = "",
)

/** Mutable only inside SessionRuntime's mutex; UI receives immutable snapshots. */
class Timeline {
    private val items = linkedMapOf<String, ChatItem>()
    private val chunks = mutableMapOf<String, StringBuilder>()
    private val changedChunks = mutableSetOf<String>()
    private var segment = 0
    private var lastChunk = ""

    fun snapshot(): List<ChatItem> {
        changedChunks.forEach { id ->
            items[id]?.let { items[id] = it.copy(text = chunks[id].toString()) }
        }
        changedChunks.clear()
        return items.values.toList()
    }

    fun clear() {
        items.clear()
        chunks.clear()
        changedChunks.clear()
        segment = 0
        lastChunk = ""
    }

    fun replaceWith(other: Timeline) {
        clear()
        other.snapshot()
        items.putAll(other.items)
        segment = other.segment
        lastChunk = other.lastChunk
    }

    fun remove(id: String) {
        items.remove(id)
        chunks.remove(id)
        changedChunks.remove(id)
    }

    fun prompt(requestId: String, turn: String, text: String, status: String) {
        val id = "user:$requestId"
        items[id] = ChatItem(id, turn, "user", text, status = status)
        lastChunk = ""
    }

    fun promptStatus(requestId: String, status: String) {
        val id = "user:$requestId"
        items[id]?.let { items[id] = it.copy(status = status) }
    }

    fun replaceTurn(turn: String, messages: List<JsonObject>) {
        items.values.filter { it.turn == turn }.map { it.id }.forEach(::remove)
        messages.forEach(::history)
        lastChunk = ""
    }

    fun history(value: JsonObject) {
        val id = value.text("message_id")
        if (id.isBlank()) return
        chunks.remove(id)
        changedChunks.remove(id)
        val tool = value.obj("tool")
        val parts = value.array("parts").mapNotNull { it as? JsonObject }
        val text =
            parts
                .joinToString("\n") { it.text("text") }
                .ifBlank { value.obj("raw_json").text("text") }
        val role =
            if (tool.isNotEmpty()) "tool"
            else value.text("role", value.text("message_type", "agent"))
        if (text.isBlank() && tool.isEmpty()) return
        items[id] =
            ChatItem(id, value.text("turn_id"), role, text, tool.text("title"), tool.text("status"))
    }

    fun frame(frame: JsonObject, turn: String) {
        val params = frame.obj("params")
        val update = params.obj("update").ifEmpty { frame.obj("update") }
        when (frame.text("method")) {
            "session/prompt" -> {
                val text =
                    params
                        .array("prompt")
                        .mapNotNull { it as? JsonObject }
                        .filter { it.text("type") == "text" }
                        .joinToString("") { it.text("text") }
                val id = "user:${frame.text("id",turn)}"
                items[id] = ChatItem(id, turn, "user", text)
                lastChunk = ""
            }
            "session/update" ->
                when (val kind = update.text("sessionUpdate")) {
                    "agent_message_chunk",
                    "agent_thought_chunk",
                    "user_message_chunk" -> {
                        val role =
                            when (kind) {
                                "user_message_chunk" -> "user"
                                "agent_thought_chunk" -> "thought"
                                else -> "agent"
                            }
                        val key = "$turn:$role"
                        if (lastChunk != key) {
                            segment++
                            lastChunk = key
                        }
                        val id = "$key:$segment"
                        val text = update.obj("content").text("text", update.text("text"))
                        val buffer =
                            chunks.getOrPut(id) { StringBuilder(items[id]?.text.orEmpty()) }
                        buffer.append(text)
                        if (items[id] == null) items[id] = ChatItem(id, turn, role, "")
                        changedChunks.add(id)
                    }
                    "tool_call",
                    "tool_call_update" -> {
                        lastChunk = ""
                        val id = "tool:$turn:${update.text("toolCallId")}"
                        val old = items[id]
                        val content =
                            update
                                .array("content")
                                .mapNotNull { it as? JsonObject }
                                .joinToString("\n") {
                                    it.obj("content").text("text", it.text("text"))
                                }
                        items[id] =
                            ChatItem(
                                id,
                                turn,
                                "tool",
                                content.ifBlank { old?.text.orEmpty() },
                                update.text("title", old?.title ?: "Tool call"),
                                update.text("status", old?.status.orEmpty()),
                            )
                    }
                }
            "session/request_permission" -> {
                lastChunk = ""
                val id = "permission:${frame.text("id")}"
                items[id] =
                    ChatItem(
                        id,
                        turn,
                        "approval",
                        "Review this request in Approvals.",
                        params.obj("toolCall").text("title", "Approval required"),
                    )
            }
        }
    }

    fun prepend(older: List<ChatItem>) {
        val current = snapshot().associateBy { it.id }
        items.clear()
        older.forEach { items[it.id] = it }
        items.putAll(current)
    }
}
