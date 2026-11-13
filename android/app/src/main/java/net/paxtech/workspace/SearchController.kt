package net.paxtech.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.paxtech.workspace.data.*

data class SearchState(
    val query: String = "",
    val groups: List<SearchGroup> = emptyList(),
    val nextCursor: String = "",
    val grouped: Boolean = true,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

/** Server-side workspace search (titles, messages and summaries), grouped per session. */
class SearchController(
    private val repo: WorkspaceRepository,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 350,
) {
    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun search(input: String) {
        val query = input.trim()
        if (query == mutable.value.query && mutable.value.error == null) return
        job?.cancel()
        if (query.isEmpty()) {
            mutable.value = SearchState()
            return
        }
        mutable.value = SearchState(query = query, loading = true)
        job =
            scope.launch {
                delay(debounceMillis)
                try {
                    val page = repo.searchWorkspace(query)
                    mutable.update {
                        if (it.query != query) it
                        else
                            it.copy(
                                groups = page.groups,
                                nextCursor = page.nextCursor,
                                grouped = page.grouped,
                                loading = false,
                            )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        if (it.query != query) it else it.copy(loading = false, error = message(e))
                    }
                }
            }
    }

    fun retry() {
        val query = mutable.value.query
        mutable.value = SearchState()
        search(query)
    }

    fun loadMore() {
        val current = mutable.value
        if (current.loading || current.loadingMore || current.nextCursor.isBlank()) return
        mutable.update { it.copy(loadingMore = true, error = null) }
        job =
            scope.launch {
                try {
                    val page =
                        repo.searchWorkspace(current.query, current.nextCursor, current.grouped)
                    mutable.update {
                        if (it.query != current.query) it
                        else
                            it.copy(
                                groups = mergeGroups(it.groups, page.groups),
                                nextCursor = page.nextCursor,
                                loadingMore = false,
                            )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update { it.copy(loadingMore = false, error = message(e)) }
                }
            }
    }

    fun clear() {
        job?.cancel()
        mutable.value = SearchState()
    }

    private fun message(e: Exception) =
        e.message?.takeIf { it.isNotBlank() } ?: "Search failed. Please try again."
}

/** A session may span pages (raw mode); keep one card per session. */
internal fun mergeGroups(old: List<SearchGroup>, new: List<SearchGroup>): List<SearchGroup> {
    val merged = LinkedHashMap<String, SearchGroup>()
    (old + new).forEach { group ->
        merged[group.key] =
            merged[group.key]?.let { it.copy(matches = it.matches + group.matches) } ?: group
    }
    return merged.values.toList()
}
