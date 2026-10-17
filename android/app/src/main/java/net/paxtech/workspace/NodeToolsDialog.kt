package net.paxtech.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun DirectoryBrowserDialog(state: DirectoryBrowserState, controller: DirectoryBrowserController) {
    Dialog(
        onDismissRequest = controller::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.85f),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose folder", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Folders inside this device's home only",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(state.page?.path ?: "Home (~)", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { controller.load("") }, enabled = !state.loading) {
                        Text("Home")
                    }
                    TextButton(
                        onClick = { controller.load(state.page!!.parent) },
                        enabled = !state.loading && !state.page?.parent.isNullOrBlank(),
                    ) {
                        Text("Up")
                    }
                }
                Row {
                    Checkbox(state.hidden, { controller.hidden(it) }, enabled = !state.loading)
                    Text("Hidden folders", Modifier.padding(top = 12.dp))
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = controller::retry) { Text("Retry") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(state.page?.entries.orEmpty(), key = { it.path }) { entry ->
                        TextButton(
                            onClick = { controller.load(entry.path) },
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(entry.name, Modifier.weight(1f))
                            Text("›")
                        }
                    }
                    if (
                        state.page?.entries?.isEmpty() == true &&
                            !state.loading &&
                            state.error == null
                    )
                        item { Text("No subfolders", Modifier.padding(16.dp)) }
                    if (!state.page?.next_after.isNullOrBlank())
                        item {
                            TextButton(onClick = controller::more, enabled = !state.loading) {
                                Text("Load more folders")
                            }
                        }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = controller::dismiss) { Text("Cancel") }
                    Button(
                        onClick = controller::choose,
                        enabled = state.page != null && !state.loading && state.error == null,
                    ) {
                        Text("Select this folder")
                    }
                }
            }
        }
    }
}

@Composable
fun NodeToolsDialog(state: NodeToolsState, controller: NodeToolsController) {
    var confirm by remember(state.node.node_id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = controller::dismiss,
        title = { Text(state.node.name.ifBlank { state.node.hostname }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (state.node.online) "Online" else "Offline · last reported version")
                Text("paxd ${state.node.paxd_version.ifBlank { "version unknown" }}")
                if (state.latest.isNotBlank()) Text("Latest stable: ${state.latest}")
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.message.isNotBlank()) Text(state.message)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.pending != null)
                    Text(
                        "Target: ${state.pending.version}. You can close this page and check later."
                    )
            }
        },
        dismissButton = { TextButton(onClick = controller::dismiss) { Text("Close") } },
        confirmButton = {
            if (state.pending != null)
                TextButton(onClick = controller::check, enabled = !state.loading) {
                    Text("Check status")
                }
            else if (state.error != null)
                TextButton(onClick = { controller.open(state.node) }) { Text("Retry") }
            else
                TextButton(
                    onClick = { confirm = true },
                    enabled =
                        !state.loading &&
                            state.node.online &&
                            state.latest.isNotBlank() &&
                            state.latest.removePrefix("v") !=
                                state.node.paxd_version.removePrefix("v"),
                ) {
                    Text(
                        if (
                            state.latest.isNotBlank() &&
                                state.latest.removePrefix("v") ==
                                    state.node.paxd_version.removePrefix("v")
                        )
                            "Up to date"
                        else "Upgrade paxd"
                    )
                }
        },
    )
    if (confirm)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Upgrade to ${state.latest}?") },
            text = {
                Text(
                    "The update waits for active work to finish. This device will disconnect briefly while paxd restarts."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        controller.upgrade()
                    }
                ) {
                    Text("Upgrade when idle")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
}
