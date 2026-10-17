package net.paxtech.workspace

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.PaxApi
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class NodeToolsTest {
    @Test
    fun directoryPagesAreLazyAndFailuresKeepNavigationRecoverable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>()
        var fail = false
        var selected = ""
        val controller =
            DirectoryBrowserController(
                scope,
                { node, path, after, hidden ->
                    calls += "$node:$path:$after:$hidden"
                    if (fail) error("Permission denied")
                    DirectoryPage(
                        "/home/me",
                        path.ifBlank { "/home/me" },
                        entries =
                            listOf(
                                DirectoryEntry(
                                    if (after.isEmpty()) "a" else "b",
                                    if (after.isEmpty()) "/home/me/a" else "/home/me/b",
                                )
                            ),
                        next_after = if (after.isEmpty()) "a" else "",
                    )
                },
                { node, path -> selected = "$node:$path" },
            )
        controller.open("first")
        assertEquals(listOf("first:::false"), calls)
        controller.more()
        assertEquals(2, controller.state.value!!.page!!.entries.size)
        fail = true
        controller.load("/home/me/a")
        assertEquals("Permission denied", controller.state.value!!.error)
        controller.choose()
        assertEquals("", selected)
        fail = false
        controller.retry()
        assertNull(controller.state.value!!.error)
        controller.choose()
        assertEquals("first:/home/me/a", selected)
        assertNull(controller.state.value)
        scope.cancel()
    }

    @Test
    fun staleDirectoryResponseCannotReplaceAnotherDevice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CompletableDeferred<Unit>()
        val controller =
            DirectoryBrowserController(
                scope,
                { node, _, _, _ ->
                    if (node == "old") withContext(NonCancellable) { old.await() }
                    DirectoryPage("/$node", "/$node")
                },
                { _, _ -> },
            )
        controller.open("old")
        controller.open("new")
        old.complete(Unit)
        yield()
        assertEquals("new", controller.state.value!!.nodeId)
        assertEquals("/new", controller.state.value!!.page!!.path)
        scope.cancel()
    }

    @Test
    fun onlyConfirmedRunningVersionIsSuccess() {
        fun progress(raw: String) = upgradeProgress(wireJson.parseToJsonElement(raw).jsonObject)
        assertFalse(
            progress(
                    """{"command":{"status":"succeeded","result":{"phase":"shutdown_committed"}}}"""
                )
                .terminal
        )
        assertFalse(
            progress("""{"maintenance_confirmation":{"status":"awaiting_running"}}""").succeeded
        )
        assertTrue(progress("""{"maintenance_confirmation":{"status":"confirmed"}}""").succeeded)
        assertFalse(
            progress("""{"maintenance_confirmation":{"status":"version_mismatch"}}""").succeeded
        )
        assertTrue(
            progress("""{"command_ack":{"status":"rejected","error":{"message":"Busy"}}}""")
                .terminal
        )
    }

    @Test
    fun reopeningChecksPersistedCommandWithoutPostingAgain() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"data":{"maintenance_confirmation":{"status":"confirmed"}}}""")
            )
            val pending = PendingUpgrade("upgrade_existing", "1.2.3")
            val vault =
                MemoryVault(
                    VaultData(
                        credential =
                            Credential(
                                server.url("/").toString(),
                                "usr_fixture",
                                "test@example.invalid",
                                "fixture",
                                "",
                                "us",
                            ),
                        pendingUpgrades = mapOf("usr_fixture:node_1" to pending),
                    )
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val controller = NodeToolsController(WorkspaceRepository(PaxApi(), vault), vault, scope)
            controller.open(Node("node_1", online = true))
            withTimeout(3000) { controller.state.first { it?.message == "Upgrade complete" } }
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(
                "/api/v1/user/self/nodes/node_1/daemon/commands/upgrade_existing",
                request.path,
            )
            assertTrue(vault.read().pendingUpgrades.isEmpty())
            assertEquals("1.2.3", controller.state.value!!.node.paxd_version)
            scope.cancel()
        }
    }
}
