package net.paxtech.workspace

import androidx.compose.ui.text.SpanStyle
import kotlinx.coroutines.runBlocking
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.ApiFailure
import net.paxtech.workspace.network.PaxApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSearchTest {
    private fun hit(session: String, id: String, title: String = "") =
        SearchHit(SearchRef("message", id), title, "snippet $id", "agent", session)

    @Test
    fun rawHitsFoldIntoOneGroupPerSessionInServerOrder() {
        val groups = groupHits(listOf(hit("a", "1", "Alpha"), hit("b", "2"), hit("a", "3")))
        assertEquals(listOf("a", "b"), groups.map { it.session_id })
        assertEquals(listOf("1", "3"), groups[0].matches.map { it.ref.id })
        assertEquals("Alpha", groups[0].title)
    }

    @Test
    fun laterPagesExtendExistingSessionCards() {
        val first = groupHits(listOf(hit("a", "1"), hit("b", "2")))
        val merged = mergeGroups(first, groupHits(listOf(hit("b", "3"), hit("c", "4"))))
        assertEquals(listOf("a", "b", "c"), merged.map { it.session_id })
        assertEquals(listOf("2", "3"), merged[1].matches.map { it.ref.id })
    }

    @Test
    fun highlightMarksEveryCaseInsensitiveOccurrence() {
        val text = highlight("Deploy then deploy again", "DEPLOY", SpanStyle())
        assertEquals(listOf(0 to 6, 12 to 18), text.spanStyles.map { it.start to it.end })
        assertTrue(highlight("anything", " ", SpanStyle()).spanStyles.isEmpty())
    }

    @Test
    fun groupedSearchFallsBackToRawResultsOnlyForTheFirstPage() = runBlocking {
        MockWebServer().use { server ->
            fun ok(data: String) =
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"code":200,"data":$data}""")
            fun unavailable() =
                MockResponse()
                    .setResponseCode(503)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"message":"Grouped search requires the search index migration to finish"}"""
                    )
            val repository =
                WorkspaceRepository(
                    PaxApi(),
                    MemoryVault(
                        VaultData(
                            credential =
                                Credential(
                                    server.url("/").toString(),
                                    "usr",
                                    "u@example.invalid",
                                    "paxu_k",
                                    "key",
                                    "us",
                                )
                        )
                    ),
                )
            server.enqueue(unavailable())
            server.enqueue(
                ok(
                    """{"hits":[{"ref":{"kind":"message","id":"m1"},"snippet":"x","session_id":"s1"}],"next_cursor":"c2"}"""
                )
            )

            val page = repository.searchWorkspace("deploy")
            assertFalse(page.grouped)
            assertEquals(listOf("s1"), page.groups.map { it.session_id })
            assertEquals("c2", page.nextCursor)
            val grouped = server.takeRequest()
            assertEquals("POST", grouped.method)
            assertEquals("/api/v1/user/self/search", grouped.path)
            assertTrue(grouped.body.readUtf8().contains("\"group_by\":\"session\""))
            assertFalse(server.takeRequest().body.readUtf8().contains("group_by"))

            // A grouped cursor is bound to grouped results and must not silently switch modes.
            server.enqueue(unavailable())
            try {
                repository.searchWorkspace("deploy", cursor = "c2")
                fail("A grouped cursor must not switch to raw results")
            } catch (expected: ApiFailure) {
                assertEquals(503, expected.status)
            }
        }
    }
}
