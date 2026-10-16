package net.paxtech.workspace

import com.mikepenz.markdown.utils.getUnescapedTextInNode
import java.net.URI
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType

internal data class MarkdownCodeContent(val code: String, val language: String?)

/** Use parser offsets: never split on backticks inside a fence or trim code indentation. */
internal fun markdownCode(content: String, node: ASTNode): MarkdownCodeContent {
    val language =
        node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)?.let {
            content.substring(it.startOffset, it.endOffset).trim()
        }
    if (node.type == MarkdownElementTypes.CODE_FENCE) {
        // The parser separates list/quote prefixes from actual code, including incomplete fences.
        val code =
            node.children
                .dropWhile { it.type != MarkdownTokenTypes.EOL }
                .drop(1)
                .filter {
                    it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT ||
                        it.type == MarkdownTokenTypes.EOL
                }
                .joinToString("") { content.substring(it.startOffset, it.endOffset) }
        return MarkdownCodeContent(code.removeSuffix("\n"), language)
    }
    // Indented code removes only the Markdown indentation, preserving further nesting.
    return MarkdownCodeContent(
        content
            .substring(node.startOffset, node.endOffset)
            .lineSequence()
            .joinToString("\n") { if (it.startsWith("    ")) it.drop(4) else it.removePrefix("\t") }
            .removeSuffix("\n"),
        language,
    )
}

internal fun safeMarkdownLink(value: String): String? =
    try {
        val uri = URI(value.trim())
        when (uri.scheme?.lowercase()) {
            "https",
            "http" ->
                if (!uri.host.isNullOrBlank() && uri.userInfo == null) uri.toASCIIString() else null
            "mailto" -> if (uri.rawSchemeSpecificPart.isNullOrBlank()) null else uri.toASCIIString()
            else -> null
        }
    } catch (_: Exception) {
        null
    }

internal fun markdownNodes(node: ASTNode): Sequence<ASTNode> = sequence {
    val pending = ArrayDeque<ASTNode>()
    pending.add(node)
    while (pending.isNotEmpty()) {
        val next = pending.removeLast()
        yield(next)
        next.children.asReversed().forEach { pending.add(it) }
    }
}

internal fun markdownImageAlt(content: String, node: ASTNode): String =
    markdownNodes(node)
        .firstOrNull {
            it.type == MarkdownElementTypes.LINK_TEXT || it.type == MarkdownElementTypes.LINK_LABEL
        }
        ?.getUnescapedTextInNode(content)
        ?.removeSurrounding("[", "]")
        .orEmpty()

internal fun markdownImageUrl(content: String, node: ASTNode, resolve: (String) -> String): String {
    val nodes = markdownNodes(node).toList()
    val direct =
        nodes
            .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
            ?.getUnescapedTextInNode(content)
    if (direct != null) return direct.removeSurrounding("<", ">")
    val label =
        nodes
            .firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
            ?.getUnescapedTextInNode(content)
    return resolve(label?.takeUnless { it == "[]" } ?: "[${markdownImageAlt(content, node)}]")
}
