package net.paxtech.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.paxtech.workspace.data.SearchGroup
import net.paxtech.workspace.data.SearchHit

/** Case-insensitive literal highlight, like the web's LiteralHighlight. */
internal fun highlight(text: String, query: String, style: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(text)
        val needle = query.trim()
        if (needle.isEmpty()) return@buildAnnotatedString
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            addStyle(style, at, at + needle.length)
            from = at + needle.length
        }
    }

private fun SearchHit.kindLabel() =
    when (ref.kind) {
        "session" -> "Title"
        "summary" -> "Summary"
        else -> "Message"
    }

internal fun LazyListScope.searchResults(
    state: SearchState,
    titleFor: (SearchGroup) -> String,
    onOpen: (SearchGroup) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
) {
    if (state.loading)
        item(key = "search-loading") {
            Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    state.error?.let { message ->
        item(key = "search-error") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
    items(state.groups, key = { "search:" + it.key }) { group ->
        SearchGroupCard(group, titleFor(group), state.query) { onOpen(group) }
    }
    if (!state.loading && state.error == null && state.groups.isEmpty())
        item(key = "search-empty") {
            WorkspaceEmptyState(
                "No matches",
                "Nothing in your sessions, messages or summaries matches “${state.query}”.",
                icon = Icons.Outlined.Search,
            )
        }
    if (state.nextCursor.isNotBlank() && !state.loading)
        item(key = "search-more") {
            WorkspaceOutlinedButton(onClick = onMore, enabled = !state.loadingMore) {
                Text(if (state.loadingMore) "Loading…" else "More results")
            }
        }
}

@Composable
private fun SearchGroupCard(group: SearchGroup, title: String, query: String, onClick: () -> Unit) {
    val mark =
        SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    val shown = group.matches.filter { it.snippet.isNotBlank() }.take(2)
    val hidden = group.matches.size - shown.size
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("search-group:${group.key}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                highlight(title, query, mark),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            shown.forEach { hit ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        hit.kindLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp).width(56.dp),
                    )
                    Text(
                        highlight(hit.snippet.replace(Regex("\\s+"), " ").trim(), query, mark),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hidden > 0 || group.matches_has_more)
                Text(
                    if (group.matches_has_more) "More matches in this session"
                    else "+$hidden more ${if (hidden == 1) "match" else "matches"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}
