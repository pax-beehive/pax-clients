package net.paxtech.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun NewProjectDialog(state: NewProjectState, controller: NewProjectController) {
    AlertDialog(
        onDismissRequest = controller::dismiss,
        title = { Text("New project") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Keep related sessions together in a project.")
                OutlinedTextField(
                    state.name,
                    controller::name,
                    label = { Text("Project name") },
                    singleLine = true,
                    enabled = !state.creating && !state.uncertain,
                    isError = state.tooLong,
                    modifier = Modifier.fillMaxWidth().testTag("project-name"),
                )
                if (state.tooLong)
                    Text(
                        "Project name is too long. Try a shorter name.",
                        color = MaterialTheme.colorScheme.error,
                    )
                state.error?.let { ErrorNotice(it) }
                if (state.creating) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (state.uncertain) TextButton(onClick = controller::dismiss) { Text("View projects") }
            else
                TextButton(
                    onClick = controller::create,
                    enabled = state.canCreate,
                    modifier = Modifier.testTag("create-project"),
                ) {
                    Text(if (state.creating) "Creating…" else "Create project")
                }
        },
        dismissButton = {
            if (!state.uncertain)
                TextButton(onClick = controller::dismiss, enabled = !state.creating) {
                    Text("Cancel")
                }
        },
    )
}
