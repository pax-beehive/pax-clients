package net.paxtech.workspace

import kotlinx.serialization.json.jsonObject
import net.paxtech.workspace.data.wireJson
import net.paxtech.workspace.session.ChatItem
import net.paxtech.workspace.session.Timeline
import org.junit.Assert.*
import org.junit.Test

class ConversationActivityTest {
    @Test
    fun groupsOnlyAdjacentActivityFromTheSameTurn() {
        val messages =
            listOf(
                ChatItem("u", "a", "user", "Question"),
                ChatItem("t", "a", "thought", "Reasoning"),
                ChatItem("cmd", "a", "tool", "Output"),
                ChatItem("reply", "a", "agent", "Answer"),
                ChatItem("cmd2", "a", "tool", "Next output"),
                ChatItem("permission", "a", "approval", "Please approve"),
                ChatItem("cmd3", "a", "tool", "Output"),
                ChatItem("cmd4", "b", "tool", "Other turn"),
            )
        val entries = conversationEntries(messages)
        assertEquals(listOf(1, 2, 1, 1, 1, 1, 1), entries.map { it.messages.size })
        assertEquals(messages, entries.flatMap { it.messages })
        assertEquals("t", entries[1].id)
        assertFalse(entries[4].isActivity)
    }

    @Test
    fun failureRemainsVisibleAfterOtherToolsCompleteAndUnknownIsNotSuccess() {
        fun tool(id: String, status: String) = ChatItem(id, "t", "tool", "", status = status)
        assertEquals("Failed", activityStatus(listOf(tool("1", "failed"), tool("2", "completed"))))
        assertEquals(
            "Running",
            activityStatus(listOf(tool("1", "completed"), tool("2", "in_progress"))),
        )
        assertEquals("", activityStatus(listOf(tool("1", ""))))
        assertEquals("", activityStatus(emptyList()))
    }

    @Test
    fun historySeparatesMixedThoughtPartsWithoutHidingAnswers() {
        val timeline = Timeline()
        timeline.history(
            wireJson
                .parseToJsonElement(
                    """{
          "message_id":"mixed","turn_id":"t","role":"assistant","parts":[
            {"part_index":2,"part_type":"text","text":"Visible answer"},
            {"part_index":0,"part_type":"agent_thought","text":"Hidden reasoning"},
            {"part_index":1,"part_type":"reasoning","text":"More reasoning"}
          ]} """
                )
                .jsonObject
        )
        assertEquals(listOf("thought", "assistant"), timeline.snapshot().map { it.role })
        assertEquals("Visible answer", timeline.snapshot().last().text)
        val restored = Timeline()
        restored.prepend(timeline)
        restored.history(
            wireJson
                .parseToJsonElement(
                    """{
          "message_id":"mixed","turn_id":"t","role":"assistant",
          "parts":[{"part_type":"text","text":"Updated answer"}]}"""
                )
                .jsonObject
        )
        assertEquals("Updated answer", restored.snapshot().single().text)
    }

    @Test
    fun storedThoughtMessageTypeOverridesAssistantRole() {
        val timeline = Timeline()
        timeline.history(
            wireJson
                .parseToJsonElement(
                    """{
          "message_id":"thought","role":"assistant","message_type":"thought",
          "parts":[{"part_type":"text","text":"Stored reasoning"}]}"""
                )
                .jsonObject
        )
        assertEquals("thought", timeline.snapshot().single().role)
    }
}
