package net.paxtech.workspace

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.*
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.*
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.*
import com.mikepenz.markdown.model.State as ParseState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

@Composable
internal fun MessageMarkdown(text: String) {
    val large = text.length > 150_000
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if (large) SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
        else RichMarkdown(text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Message", text)))
                        copied = true
                    }
                }
            ) {
                if (copied)
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = "Message copied",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else
                    Icon(
                        painterResource(R.drawable.ic_copy),
                        contentDescription = "Copy message",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
}

@Composable
internal fun RichMarkdown(text: String) {
    // Each parse owns its parser and link map. Cancelled old chunks cannot publish newer input
    // with an old AST; the last successful rendering stays visible until its replacement is ready.
    val parsed by
        produceState<ParseState?>(null, text) {
            value = parseMarkdownFlow(text).first { it !is ParseState.Loading }
        }
    val body = MaterialTheme.typography.bodyMedium
    val foreground = LocalContentColor.current
    val context = LocalContext.current
    val external = LocalUriHandler.current
    val uriHandler =
        remember(external, context) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    val safe = safeMarkdownLink(uri)
                    if (safe == null)
                        Toast.makeText(
                                context,
                                "This link cannot be opened on Android.",
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    else
                        try {
                            external.openUri(safe)
                        } catch (_: Exception) {
                            Toast.makeText(
                                    context,
                                    "No app is available to open this link.",
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                }
            }
        }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        val ready = parsed
        if (ready == null || ready is ParseState.Error) {
            SelectionContainer { Text(text, style = body) }
        } else
            SelectionContainer {
                Markdown(
                    state = ready,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        markdownColor(
                            text = foreground,
                            codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    typography =
                        markdownTypography(
                            h1 = MaterialTheme.typography.headlineSmall,
                            h2 = MaterialTheme.typography.titleLarge,
                            h3 = MaterialTheme.typography.titleMedium,
                            h4 = MaterialTheme.typography.titleSmall,
                            h5 = body.copy(fontWeight = FontWeight.Bold),
                            h6 = body.copy(fontWeight = FontWeight.SemiBold),
                            text = body,
                            paragraph = body,
                            ordered = body,
                            bullet = body,
                            list = body,
                            table = body,
                            quote = body.copy(fontStyle = FontStyle.Italic),
                            code =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                        ),
                    animations = markdownAnimations(animateTextSize = { this }),
                    annotator =
                        markdownAnnotator { content, child ->
                            when (child.type) {
                                GFMElementTypes.INLINE_MATH,
                                GFMElementTypes.BLOCK_MATH,
                                MarkdownTokenTypes.HTML_TAG -> {
                                    append(content.substring(child.startOffset, child.endOffset))
                                    true
                                }
                                MarkdownElementTypes.IMAGE -> {
                                    val alt = markdownImageAlt(content, child)
                                    append("[Image${if (alt.isBlank()) "" else ": $alt"}]")
                                    true
                                }
                                else -> false
                            }
                        },
                    components =
                        markdownComponents(
                            orderedList = { AlignedMarkdownOrderedList(it) },
                            paragraph = { model ->
                                Column {
                                    MarkdownParagraph(model.content, model.node)
                                    markdownNodes(model.node)
                                        .filter { it.type == MarkdownElementTypes.IMAGE }
                                        .forEach {
                                            MarkdownImageCard(
                                                model.content,
                                                it,
                                                ready.referenceLinkHandler,
                                            )
                                        }
                                }
                            },
                            checkbox = {
                                MarkdownCheckBox(it.content, it.node, it.typography.text)
                            },
                            codeFence = { MarkdownCodePanel(markdownCode(it.content, it.node)) },
                            codeBlock = { MarkdownCodePanel(markdownCode(it.content, it.node)) },
                            table = {
                                MarkdownTable(
                                    it.content,
                                    it.node,
                                    it.typography.table,
                                    headerBlock = { content, node, width, style ->
                                        MarkdownTableHeader(
                                            content,
                                            node,
                                            width,
                                            style,
                                            maxLines = Int.MAX_VALUE,
                                        )
                                    },
                                    rowBlock = { content, node, width, style ->
                                        MarkdownTableRow(
                                            content,
                                            node,
                                            width,
                                            style,
                                            maxLines = Int.MAX_VALUE,
                                        )
                                    },
                                )
                            },
                            image = {
                                MarkdownImageCard(it.content, it.node, ready.referenceLinkHandler)
                            },
                            custom = { type, model ->
                                when (type) {
                                    MarkdownElementTypes.LINK_DEFINITION -> Unit
                                    MarkdownElementTypes.HTML_BLOCK,
                                    GFMElementTypes.BLOCK_MATH,
                                    GFMElementTypes.INLINE_MATH ->
                                        Text(
                                            model.content.substring(
                                                model.node.startOffset,
                                                model.node.endOffset,
                                            ),
                                            style = body,
                                        )
                                    // Container markers are already represented by list/quote
                                    // components.
                                    // Recurse like the renderer default instead of printing syntax
                                    // tokens.
                                    else ->
                                        model.node.children.forEach {
                                            MarkdownElement(
                                                it,
                                                com.mikepenz.markdown.compose
                                                    .LocalMarkdownComponents
                                                    .current,
                                                model.content,
                                                includeSpacer = false,
                                            )
                                        }
                                }
                            },
                        ),
                )
            }
    }
}

/** A shared number gutter and first-line baseline keep wrapping/nested items aligned. */
@Composable
private fun AlignedMarkdownOrderedList(model: MarkdownComponentModel) {
    val items = model.node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    val start =
        items.firstOrNull()?.let {
            model.content
                .substring(it.startOffset, it.endOffset)
                .takeWhile(Char::isDigit)
                .toIntOrNull()
        } ?: 1
    val style = model.typography.ordered
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gutter =
        with(density) {
                measurer
                    .measure("${start + items.size.coerceAtLeast(1) - 1}.", style)
                    .size
                    .width
                    .toDp()
            }
            .coerceAtLeast(20.dp) + 8.dp
    MarkdownListItems(
        model.content,
        model.node,
        model.listDepth,
        markerModifier = { Modifier.width(gutter).padding(end = 8.dp).alignBy(FirstBaseline) },
        listModifier = { Modifier.weight(1f).alignBy(FirstBaseline) },
    ) { index, listNumber, _ ->
        Text(
            "${listNumber + index}.",
            Modifier.fillMaxWidth(),
            style = style,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun MarkdownCodePanel(block: MarkdownCodeContent) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlighted by
        produceState<Pair<String, AnnotatedString>?>(null, block, dark) {
            value =
                withContext(Dispatchers.Default) {
                    val language =
                        block.language?.let {
                            runCatching { SyntaxLanguage.getByName(it) }.getOrNull()
                        }
                    val result =
                        if (language == null || block.code.length > 20_000)
                            AnnotatedString(block.code)
                        else
                            try {
                                val spans =
                                    Highlights.Builder()
                                        .theme(SyntaxThemes.default(darkMode = dark))
                                        .language(language)
                                        .code(block.code)
                                        .build()
                                        .getHighlights()
                                buildAnnotatedString {
                                    append(block.code)
                                    spans.forEach {
                                        val style =
                                            when (it) {
                                                is ColorHighlight ->
                                                    SpanStyle(
                                                        color =
                                                            Color(it.rgb).copy(alpha = 1f).let {
                                                                color ->
                                                                if (dark)
                                                                    lerp(color, Color.White, .25f)
                                                                else color
                                                            }
                                                    )
                                                is BoldHighlight ->
                                                    SpanStyle(fontWeight = FontWeight.Bold)
                                            }
                                        if (it.location.start >= 0 && it.location.end <= length)
                                            addStyle(style, it.location.start, it.location.end)
                                    }
                                }
                            } catch (_: Exception) {
                                AnnotatedString(block.code)
                            }
                    block.code to result
                }
        }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied, block.code) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    block.language?.take(40).orEmpty().ifBlank { "Code" },
                    Modifier.weight(1f).padding(vertical = 14.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("Code", block.code))
                            )
                            copied = true
                        }
                    }
                ) {
                    Text(if (copied) "Copied" else "Copy code")
                }
            }
            val wrap = remember(block.code) { block.code.lineSequence().any { it.length > 2000 } }
            Text(
                highlighted?.takeIf { it.first == block.code }?.second
                    ?: AnnotatedString(block.code),
                Modifier.then(
                        if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())
                    )
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                softWrap = wrap,
            )
        }
    }
}

@Composable
private fun MarkdownImageCard(content: String, node: ASTNode, references: ReferenceLinkHandler) {
    val url = markdownImageUrl(content, node, references::find)
    val alt = markdownImageAlt(content, node).ifBlank { "Image" }
    AttachmentCard(net.paxtech.workspace.session.attachmentFromUri(url, alt, "image/png"))
}
