package net.paxtech.workspace.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import net.paxtech.workspace.network.ApiFailure

// Mirrors pax-console src/features/api/workspace-search*.ts and manager
// POST /api/v1/user/:user_id/search (group_by=session), with the same raw fallback.

@Serializable data class SearchRef(val kind: String = "", val id: String = "")

@Serializable
data class SearchHit(
    val ref: SearchRef = SearchRef(),
    val title: String = "",
    val snippet: String = "",
    val agent_id: String = "",
    val session_id: String = "",
)

@Serializable
data class SearchGroup(
    val key: String = "",
    val title: String = "",
    val agent_id: String = "",
    val session_id: String = "",
    val matches: List<SearchHit> = emptyList(),
    val matches_has_more: Boolean = false,
)

@Serializable
private data class SearchPageWire(
    val hits: List<SearchHit> = emptyList(),
    val groups: List<SearchGroup>? = null,
    val next_cursor: String = "",
)

/** One page of results, always presented per session. `grouped` pins the cursor's mode. */
data class SearchPage(val groups: List<SearchGroup>, val nextCursor: String, val grouped: Boolean)

/** Raw hits (older index) are folded into per-session groups locally, keeping server order. */
internal fun groupHits(hits: List<SearchHit>): List<SearchGroup> =
    hits
        .groupBy { it.session_id.ifBlank { it.ref.kind + ":" + it.ref.id } }
        .map { (key, matches) ->
            SearchGroup(
                key = key,
                title = matches.firstOrNull { it.title.isNotBlank() }?.title.orEmpty(),
                agent_id = matches.first().agent_id,
                session_id = matches.first().session_id,
                matches = matches,
            )
        }

suspend fun WorkspaceRepository.searchWorkspace(
    query: String,
    cursor: String = "",
    grouped: Boolean = true,
    limit: Int = 20,
): SearchPage {
    suspend fun request(groupBy: Boolean): SearchPage {
        val page =
            wireJson.decodeFromJsonElement<SearchPageWire>(
                post(
                    listOf("search"),
                    buildJsonObject {
                        put("query", query)
                        if (groupBy) put("group_by", "session")
                        if (cursor.isNotBlank()) put("cursor", cursor)
                        put("limit", limit)
                    },
                )
            )
        val groups = if (groupBy) page.groups ?: throw ApiFailure(502, "Grouped search is unavailable. Please try again.")
            else groupHits(page.hits)
        return SearchPage(groups, page.next_cursor, groupBy)
    }
    if (!grouped) return request(false)
    return try {
        request(true)
    } catch (e: ApiFailure) {
        // Manager answers 503 while the grouped index migrates. A grouped cursor is not valid
        // for raw results, so only the first page may switch modes (as the web client does).
        if (e.status != 503 || cursor.isNotBlank()) throw e
        request(false)
    }
}

/** Search hits carry agent and session ids only; opening a session also needs its node. */
suspend fun WorkspaceRepository.sessionById(sessionId: String): AgentSession =
    wireJson.decodeFromJsonElement(get("sessions", sessionId))
