package net.paxtech.workspace.session

import java.net.URI
import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*

/** Only explicit ACP content blocks and durable artifact parts become attachments. */
data class ChatAttachment(
    val name: String,
    val mimeType: String = "application/octet-stream",
    val uri: String = "",
    val base64: String? = null,
    val inlineText: String? = null,
    val size: Long? = null,
)

internal val rasterImageTypes =
    setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/avif")
internal const val maxAttachmentBytes = 25 * 1024 * 1024
internal const val maxInlineAttachmentChars = 4 * 1024 * 1024

internal fun attachmentName(value: String): String =
    value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filterNot { it.isISOControl() }
        .trim()
        .take(160)
        .takeUnless { it in setOf("", ".", "..") } ?: "Attachment"

internal fun attachmentFromUri(
    uri: String,
    name: String = "",
    mime: String = "",
    size: Long? = null,
): ChatAttachment =
    ChatAttachment(
        attachmentName(
            name.ifBlank {
                runCatching { URI(uri).path.orEmpty().substringAfterLast('/') }.getOrDefault("")
            }
        ),
        mime.ifBlank { "application/octet-stream" },
        uri = uri,
        size = size,
    )

internal fun acpAttachments(content: JsonElement?): List<ChatAttachment> =
    acpAttachments(content, 0)

private fun acpAttachments(content: JsonElement?, depth: Int): List<ChatAttachment> {
    if (depth > 8) return emptyList()
    if (content is JsonArray)
        return content.take(64).flatMap { acpAttachments(it, depth + 1) }.distinct()
    val block = content as? JsonObject ?: return emptyList()
    val mime = block.text("mimeType", "application/octet-stream")
    return when (block.text("type")) {
        "image",
        "audio" ->
            listOf(
                ChatAttachment(
                    attachmentName(
                        block.text(
                            "name",
                            if (block.text("type") == "image") "Agent image" else "Agent audio",
                        )
                    ),
                    mime,
                    base64 = block.text("data"),
                )
            )
        "resource_link" ->
            block
                .text("uri")
                .takeIf { it.isNotBlank() }
                ?.let {
                    listOf(
                        attachmentFromUri(
                            it,
                            block.text("title", block.text("name")),
                            mime,
                            block.number("size").takeIf { n -> n > 0 },
                        )
                    )
                }
                .orEmpty()
        "resource" -> {
            val resource = block.obj("resource")
            listOf(
                attachmentFromUri(
                        resource.text("uri"),
                        block.text("name"),
                        resource.text("mimeType"),
                    )
                    .copy(
                        base64 = (resource["blob"] as? JsonPrimitive)?.contentOrNull,
                        inlineText = (resource["text"] as? JsonPrimitive)?.contentOrNull,
                    )
            )
        }
        "content" -> acpAttachments(block["content"], depth + 1)
        "text" -> {
            val text = block.text("text")
            val marker =
                if (text.length <= 16384 && text.trimStart().startsWith("{"))
                    runCatching {
                            wireJson
                                .parseToJsonElement(text)
                                .jsonObject
                                .obj("pax_artifact_publication")
                        }
                        .getOrNull()
                else null
            val id = marker?.text("publication_id").orEmpty()
            if (Regex("[A-Za-z0-9_-]{1,128}").matches(id))
                listOf(
                    attachmentFromUri(
                        "artifact-publication://$id/main",
                        marker?.text("filename", "Published file").orEmpty(),
                    )
                )
            else emptyList()
        }
        else -> emptyList()
    }
}

internal fun frameAttachments(raw: JsonObject): List<ChatAttachment> {
    val frame = (raw["frame"] as? JsonObject) ?: raw
    val params = frame.obj("params")
    return when (frame.text("method")) {
        "session/prompt" -> acpAttachments(params["prompt"])
        "session/update" -> acpAttachments(params.obj("update")["content"])
        else -> acpAttachments(frame)
    }
}

internal fun historyAttachments(message: JsonObject): List<ChatAttachment> =
    buildList {
            addAll(frameAttachments(message.obj("raw_json")))
            message.array("parts").forEach { element ->
                val part = element as? JsonObject ?: return@forEach
                val uri = part.text("artifact_uri")
                if (part.text("part_type") == "artifact") {
                    val publication = part.obj("payload_json").text("publication_id")
                    val target =
                        uri.ifBlank {
                            if (publication.isNotBlank()) "artifact-publication://$publication/main"
                            else ""
                        }
                    if (target.isNotBlank())
                        add(
                            attachmentFromUri(
                                target,
                                part.obj("payload_json").text("filename", "Published file"),
                            )
                        )
                }
                addAll(frameAttachments(part.obj("payload_json")))
            }
        }
        .distinct()
        .take(64)
