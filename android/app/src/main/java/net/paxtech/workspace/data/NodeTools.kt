package net.paxtech.workspace.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class DirectoryEntry(val name: String, val path: String)

@Serializable
data class DirectoryPage(
    val home: String,
    val path: String,
    val parent: String = "",
    val entries: List<DirectoryEntry> = emptyList(),
    val next_after: String = "",
)

@Serializable data class PendingUpgrade(val commandId: String, val version: String)

suspend fun WorkspaceRepository.directories(
    nodeId: String,
    path: String = "",
    after: String = "",
    hidden: Boolean = false,
): DirectoryPage {
    val value =
        get(
                "nodes",
                nodeId,
                "daemon",
                "directories",
                query = mapOf("path" to path, "after" to after, "show_hidden" to "$hidden"),
            )
            .jsonObject
    check(value.obj("error").isEmpty()) {
        value.obj("error").text("message", "Directory browsing requires a newer paxd")
    }
    check(value.containsKey("directories")) {
        "Directory browsing is unavailable. Update paxd and try again."
    }
    return wireJson.decodeFromJsonElement(value.getValue("directories"))
}

suspend fun WorkspaceRepository.latestPaxd(node: Node): String {
    require(node.os.isNotBlank() && node.arch.isNotBlank()) {
        "This device has not reported its platform yet"
    }
    val c = credential()
    val value =
        api.json(
                api.request(
                    c.origin,
                    listOf("api", "v1", "public", "paxd", "download"),
                    c.apiKey,
                    query = mapOf("platform" to "${node.os}/${node.arch}", "tags" to "stable"),
                )
            )
            .jsonObject
    return value.text("version").also {
        check(it.isNotBlank()) { "No stable release is available for this device" }
    }
}

/** An accepted command is not proof that the new process is running. */
data class UpgradeProgress(
    val message: String,
    val terminal: Boolean = false,
    val succeeded: Boolean = false,
)

fun upgradeProgress(value: JsonObject): UpgradeProgress {
    val confirmation = value.obj("maintenance_confirmation").text("status")
    if (confirmation == "confirmed") return UpgradeProgress("Upgrade complete", true, true)
    if (confirmation == "version_mismatch")
        return UpgradeProgress(
            "Device reconnected with a different version. Check the device before retrying.",
            true,
        )
    val command = value.obj("command").ifEmpty { value.obj("command_ack") }
    if (command.text("status") in setOf("failed", "rejected"))
        return UpgradeProgress(
            command.text("error_message").ifBlank {
                command.obj("error").text("message", "Upgrade failed")
            },
            true,
        )
    val phase = command.obj("result").text("phase")
    return UpgradeProgress(
        when (phase) {
            "pending_idle",
            "draining" -> "Waiting for active work to finish"
            "staging",
            "staged" -> "Downloading and verifying update"
            "activating",
            "shutdown_committed" -> "Waiting for the device to reconnect"
            else ->
                if (confirmation == "awaiting_running") "Waiting for paxd to start"
                else "Checking upgrade status"
        }
    )
}
