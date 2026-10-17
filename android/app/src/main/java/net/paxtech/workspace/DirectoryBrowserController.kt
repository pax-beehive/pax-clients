package net.paxtech.workspace

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.paxtech.workspace.data.*

data class DirectoryBrowserState(
    val nodeId: String,
    val page: DirectoryPage? = null,
    val loading: Boolean = false,
    val hidden: Boolean = false,
    val error: String? = null,
    val requestedPath: String = "",
    val requestedAfter: String = "",
)

class DirectoryBrowserController(
    private val scope: CoroutineScope,
    private val read: suspend (String, String, String, Boolean) -> DirectoryPage,
    private val selected: (String, String) -> Unit,
) {
    private val mutable = MutableStateFlow<DirectoryBrowserState?>(null)
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun open(node: String) {
        dismiss()
        mutable.value = DirectoryBrowserState(node)
        load("")
    }

    fun dismiss() {
        job?.cancel()
        mutable.value = null
    }

    fun hidden(value: Boolean) {
        mutable.update { it?.copy(hidden = value) }
        load(mutable.value?.page?.path.orEmpty())
    }

    fun retry() {
        mutable.value?.let { load(it.requestedPath, it.requestedAfter) }
    }

    fun more() {
        mutable.value?.page?.let { if (it.next_after.isNotEmpty()) load(it.path, it.next_after) }
    }

    fun load(path: String, after: String = "") {
        val current = mutable.value ?: return
        job?.cancel()
        mutable.value =
            current.copy(loading = true, error = null, requestedPath = path, requestedAfter = after)
        job =
            scope.launch {
                try {
                    val page = read(current.nodeId, path, after, current.hidden)
                    ensureActive()
                    mutable.value =
                        current.copy(
                            loading = false,
                            error = null,
                            page =
                                if (after.isEmpty()) page
                                else
                                    page.copy(
                                        entries =
                                            (current.page.orEmptyEntries() + page.entries)
                                                .distinctBy { it.path }
                                    ),
                            requestedPath = path,
                        )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.update {
                        it?.copy(loading = false, error = e.message ?: "Cannot read this directory")
                    }
                }
            }
    }

    fun choose() {
        val current = mutable.value ?: return
        if (current.loading || current.error != null) return
        current.page?.let {
            selected(current.nodeId, it.path)
            dismiss()
        }
    }
}

private fun DirectoryPage?.orEmptyEntries() = this?.entries.orEmpty()
