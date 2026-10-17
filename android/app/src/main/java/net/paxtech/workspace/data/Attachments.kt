package net.paxtech.workspace.data

import java.net.URI
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import net.paxtech.workspace.session.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class AttachmentContent(val name: String, val mimeType: String, val bytes: ByteArray)

internal data class AttachmentRoute(
    val collection: String,
    val id: String,
    val ref: String = "main",
)

internal fun attachmentRoute(uri: String): AttachmentRoute? {
    // Paxd localizes uploaded attachments in an att_<48 hex> directory. This maps
    // only that identifier back to the owner-checked API; it never reads a path.
    val local = runCatching { URI(uri) }.getOrNull()
    if (local?.scheme == "file" && local.authority.isNullOrEmpty()) {
        val id = local.path.orEmpty().split('/').dropLast(1).lastOrNull().orEmpty()
        if (Regex("att_[a-f0-9]{48}").matches(id)) return AttachmentRoute("attachments", id)
    }

    val match =
        Regex(
                "^(artifact-publication|artifact|attachment)://([A-Za-z0-9_-]{1,128})(?:/([A-Za-z0-9_.-]{1,128}))?$"
            )
            .matchEntire(uri) ?: return null
    val collection =
        when (match.groupValues[1]) {
            "artifact-publication" -> "artifact-publications"
            "artifact" -> "artifacts"
            else -> "attachments"
        }
    val ref = match.groupValues[3].ifBlank { "main" }
    if (ref == "." || ref == "..") return null
    return AttachmentRoute(collection, match.groupValues[2], ref)
}

internal fun attachmentHttpsUrl(value: String): String? =
    value
        .toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
        ?.toString()

internal suspend fun WorkspaceRepository.attachmentContent(
    target: AgentSession,
    attachment: ChatAttachment,
): AttachmentContent =
    withContext(Dispatchers.IO) {
        require(attachment.size == null || attachment.size <= maxAttachmentBytes) {
            "This file is larger than the 25 MB mobile download limit."
        }
        attachment.base64?.let {
            require(it.isNotBlank() && it.length <= maxInlineAttachmentChars) {
                "The embedded file is empty or too large. Ask the agent to publish it as a file."
            }
            val bytes =
                try {
                    Base64.getDecoder().decode(it)
                } catch (_: IllegalArgumentException) {
                    error("The embedded file is invalid.")
                }
            return@withContext AttachmentContent(attachment.name, attachment.mimeType, bytes)
        }
        attachment.inlineText?.let {
            require(it.length <= maxInlineAttachmentChars) {
                "The embedded file is too large. Ask the agent to publish it as a file."
            }
            return@withContext AttachmentContent(
                attachment.name,
                attachment.mimeType,
                it.toByteArray(),
            )
        }
        var name = attachment.name
        var mime = attachment.mimeType
        val route = attachmentRoute(attachment.uri)
        val url =
            if (route != null) {
                val path =
                    if (route.collection == "attachments")
                        arrayOf(route.collection, route.id, "content")
                    else arrayOf(route.collection, route.id, "content", route.ref)
                val ticket =
                    get(
                            *path,
                            query =
                                if (route.collection == "attachments") mapOf("ticket" to "1")
                                else emptyMap(),
                        )
                        .jsonObject
                if (route.collection == "artifact-publications") {
                    val publication = ticket.obj("publication")
                    check(
                        publication.text("publication_id") == route.id &&
                            publication.text("session_id") == target.session_id &&
                            publication.text("agent_id") == target.agent_id &&
                            publication.text("node_id") == target.node_id
                    ) {
                        "File does not belong to this session."
                    }
                    check(ticket.text("status") == "available") {
                        "The agent is still uploading this file. Try again shortly."
                    }
                    name = publication.text("filename", name)
                }
                if (route.collection == "artifacts") {
                    val artifact = ticket.obj("artifact")
                    check(
                        artifact.text("artifact_id") == route.id &&
                            artifact.text("session_id") == target.session_id
                    ) {
                        "File does not belong to this session."
                    }
                }
                val content = ticket.obj("content")
                require(content.number("size_bytes") <= maxAttachmentBytes) {
                    "This file is larger than the 25 MB mobile download limit."
                }
                name = content.text("filename", name)
                mime = content.text("content_type", mime)
                attachmentHttpsUrl(ticket.text("url"))
                    ?: error("The file download address is invalid.")
            } else
                attachmentHttpsUrl(attachment.uri)
                    ?: error(
                        if (attachment.uri.startsWith("file:") || attachment.uri.startsWith("/"))
                            "This file is on the agent's computer. Ask the agent to publish it with publish_artifact."
                        else "This file link is not supported. Ask the agent to publish the file."
                    )
        // The signed/object URL is fetched without the user's gateway Authorization header.
        val downloaded = api.downloadAttachment(url, maxAttachmentBytes)
        AttachmentContent(
            attachmentName(name),
            downloaded.second.takeIf { it != "application/octet-stream" } ?: mime,
            downloaded.first,
        )
    }
