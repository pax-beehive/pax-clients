package net.paxtech.workspace

import android.content.ClipData
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.paxtech.workspace.data.*
import net.paxtech.workspace.session.*

private val tabs = listOf("Home", "Approvals", "Settings")

@Composable
fun WorkspaceShell(vm: WorkspaceViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) vm.setForeground(false)
        }
        lifecycle.addObserver(observer)
        vm.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            vm.setForeground(false)
        }
    }
    val nav = rememberNavController()
    LaunchedEffect(state.selected?.session_id) {
        if (state.selected != null && nav.currentDestination?.route != "session")
            nav.navigate("session")
        if (state.selected == null && nav.currentDestination?.route == "session") nav.popBackStack()
    }
    if (state.credential == null || state.login != null) {
        LoginScreen(state, vm)
        return
    }
    NavHost(navController = nav, startDestination = "workspace") {
        composable("workspace") { WorkspaceHome(state, vm) }
        composable("session") {
            androidx.activity.compose.BackHandler { vm.close() }
            state.selected?.let { SessionScreen(it, state, session, vm) }
        }
    }
}

@Composable
private fun LoginScreen(state: WorkspaceState, vm: WorkspaceViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()
    var opened by rememberSaveable { mutableStateOf("") }
    var browserError by remember { mutableStateOf<String?>(null) }
    fun launchBrowser(url: String) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
            browserError = null
        } catch (_: Exception) {
            browserError = "Install or enable a browser to finish signing in."
        }
    }
    LaunchedEffect(state.login?.url) {
        state.login
            ?.url
            ?.takeIf { it != opened }
            ?.let {
                opened = it
                launchBrowser(it)
            }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "PAX",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Workspace",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "PaxWorkspace",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Sign in to your workspace",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "Continue your agent sessions and review approvals from your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.loading || state.working)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.login == null) {
                        WorkspaceButton(
                            onClick = vm::startLogin,
                            enabled = !state.working && !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sign in")
                        }
                        Text(
                            "Use your PaxWorkspace account in the browser.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("Confirm sign-in in your browser, then return here.")
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.login.candidates.forEach {
                                    Text(
                                        "${it.region.uppercase()}: ${it.code}",
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                        WorkspaceButton(
                            onClick = { launchBrowser(state.login.url) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Open sign-in page")
                        }
                        TextButton(
                            onClick = {
                                copyScope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "PaxWorkspace sign-in",
                                                state.login.url,
                                            )
                                        )
                                    )
                                }
                            }
                        ) {
                            Text("Copy link to use another browser")
                        }
                        Row {
                            TextButton(onClick = vm::pollLogin) { Text("Check sign-in") }
                            TextButton(onClick = vm::cancelLogin) { Text("Cancel") }
                        }
                    }
                    (state.error ?: browserError)?.let { ErrorNotice(it) }
                }
            }
            Text(
                "Your agents keep running on your computers.",
                Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceHome(state: WorkspaceState, vm: WorkspaceViewModel) {
    var tab by rememberSaveable { mutableStateOf("Home") }
    var filter by rememberSaveable { mutableStateOf("") }
    var project by rememberSaveable { mutableStateOf("") }
    var signOut by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (tab == "Home") "Workspace" else tab,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    if (tab in listOf("Projects", "Devices"))
                        IconButton(onClick = { tab = "Settings" }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to settings")
                        }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Outlined.Refresh, "Refresh")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
                tabs.forEach { label ->
                    NavigationBarItem(
                        selected =
                            tab == label ||
                                (label == "Settings" && tab in listOf("Projects", "Devices")),
                        onClick = { tab = label },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        icon = {
                            Icon(
                                when (label) {
                                    "Home" -> Icons.Outlined.Home
                                    "Devices" -> Icons.Outlined.Phone
                                    "Approvals" -> Icons.Outlined.CheckCircle
                                    "Settings" -> Icons.Outlined.Settings
                                    else -> Icons.AutoMirrored.Outlined.List
                                },
                                null,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { ErrorNotice(it) } }
            when (tab) {
                "Home" -> {
                    item {
                        OutlinedTextField(
                            filter,
                            { filter = it },
                            label = { Text("Search sessions") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Recent sessions",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { tab = "Projects" }) { Text("Projects") }
                        }
                    }
                    if (project.isNotEmpty())
                        item {
                            TextButton(onClick = { project = "" }) { Text("Show all projects") }
                        }
                    val shown =
                        state.workspace.sessions.filter {
                            (project.isEmpty() || it.primary_project_id == project) &&
                                (filter.isBlank() || it.title.contains(filter, true))
                        }
                    items(shown, key = { it.session_id }) { target ->
                        Surface(
                            onClick = { vm.open(target) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            Column(
                                Modifier.padding(vertical = 16.dp, horizontal = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    target.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    target.runtime_status.replace('_', ' '),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (target.encrypted)
                                    Text(
                                        "End-to-end encrypted",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                else if (target.preview.isNotBlank())
                                    Text(
                                        target.preview,
                                        maxLines = 2,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                            }
                        }
                    }
                    if (shown.isEmpty() && !state.loading)
                        item {
                            Text(
                                "No sessions to show. Start a session from your workspace, then refresh."
                            )
                        }
                    if (state.workspace.hasMore)
                        item {
                            TextButton(onClick = { vm.refresh(true) }, enabled = !state.loading) {
                                Text("Load more sessions")
                            }
                        }
                }
                "Projects" -> {
                    items(state.workspace.projects, key = { it.project_id }) { item ->
                        ListItem(
                            headlineContent = { Text(item.display_name) },
                            trailingContent = {
                                TextButton(
                                    onClick = {
                                        project = item.project_id
                                        tab = "Home"
                                    }
                                ) {
                                    Text("Sessions")
                                }
                            },
                        )
                    }
                    if (state.workspace.projects.isEmpty() && !state.loading)
                        item { Text("No projects yet.") }
                }
                "Devices" -> {
                    items(state.workspace.nodes, key = { it.node_id }) { item ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    item.name.ifBlank {
                                        item.hostname.ifBlank { "Connected device" }
                                    }
                                )
                            },
                            supportingContent = {
                                Text("${item.os} · ${if(item.online) "Online" else "Offline"}")
                            },
                        )
                    }
                    if (state.workspace.nodes.isEmpty() && !state.loading)
                        item { Text("No connected devices yet.") }
                }
                "Approvals" -> {
                    items(state.workspace.approvals, key = { it.approval_id }) { approval ->
                        ApprovalCard(approval, state.working) { option ->
                            vm.decide(approval, option)
                        }
                    }
                    if (state.workspace.approvals.isEmpty() && !state.loading)
                        item { Text("No pending approvals.") }
                }
                "Settings" -> {
                    item {
                        Text(
                            "Workspace",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { tab = "Devices" },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Devices")
                        }
                        TextButton(
                            onClick = { tab = "Projects" },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Projects")
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item {
                        Text(state.credential!!.email, style = MaterialTheme.typography.titleMedium)
                        Text("Account region: ${state.credential.region.uppercase()}")
                    }
                    item {
                        Text(
                            "PaxWorkspace for Android · ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    item {
                        WorkspaceOutlinedButton(onClick = { signOut = true }) {
                            Text("Sign out of this device")
                        }
                    }
                }
            }
        }
    }
    if (signOut)
        AlertDialog(
            onDismissRequest = { signOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "This revokes this app's access and removes its local encryption keys. Your agent sessions keep running."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        signOut = false
                        vm.signOut()
                    }
                ) {
                    Text("Sign out")
                }
            },
            dismissButton = { TextButton(onClick = { signOut = false }) { Text("Cancel") } },
        )
}

@Composable
private fun ApprovalCard(
    approval: Approval,
    working: Boolean,
    onDecision: (ApprovalOption) -> Unit,
) {
    var selected by remember { mutableStateOf<ApprovalOption?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(approval.title, style = MaterialTheme.typography.titleMedium)
            Text(approval.description)
            Text(approval.status, style = MaterialTheme.typography.labelSmall)
            approval.options
                .filter { it.option_id.isNotBlank() }
                .forEach { option ->
                    WorkspaceOutlinedButton(
                        onClick = { selected = option },
                        enabled = !working && approval.status in setOf("", "pending"),
                    ) {
                        Text(option.label.ifBlank { option.option_id })
                    }
                }
        }
    }
    selected?.let { option ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(option.label.ifBlank { "Confirm decision" }) },
            text = {
                Text(
                    "${approval.title}\n${option.scope.ifBlank { "Apply this decision to the requested action." }}"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selected = null
                        onDecision(option)
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionScreen(
    target: AgentSession,
    state: WorkspaceState,
    session: SessionState,
    vm: WorkspaceViewModel,
) {
    var draft by rememberSaveable(target.session_id) { mutableStateOf("") }
    var submitted by rememberSaveable(target.session_id) { mutableStateOf("") }
    LaunchedEffect(session.connection) {
        if (session.connection == "Needs reconnect" && draft.isBlank()) draft = submitted
    }
    val list = rememberLazyListState()
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(session.messages.lastOrNull()) {
        if (atBottom && session.messages.isNotEmpty())
            list.scrollToItem(session.messages.lastIndex + if (session.hasOlder) 1 else 0)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            target.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(session.connection, style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = vm::close) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::reconnect) {
                        Icon(Icons.Outlined.Refresh, "Reconnect")
                    }
                },
            )
        },
        bottomBar = {
            if (!session.locked)
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        session.error?.let { ErrorNotice(it) }
                        OutlinedTextField(
                            draft,
                            { draft = it },
                            placeholder = { Text("Message your agent") },
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 6,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (session.busy) TextButton(onClick = vm::stop) { Text("Stop") }
                            WorkspaceButton(
                                onClick = {
                                    val text = draft
                                    submitted = text
                                    draft = ""
                                    vm.send(text)
                                },
                                enabled =
                                    draft.isNotBlank() &&
                                        !session.busy &&
                                        session.connection == "Connected",
                            ) {
                                Text("Send")
                            }
                        }
                    }
                }
        },
    ) { padding ->
        if (session.locked) {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text("Authorize this device", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "This session is encrypted. Confirm access on the computer running this agent."
                    )
                }
                item {
                    WorkspaceButton(onClick = vm::beginPairing) { Text("Prepare authorization") }
                }
                state.pairing?.let { pending ->
                    item {
                        Text("Run this command on your agent's computer:")
                        SelectionContainer {
                            Text(
                                vm.pairingCommand(pending),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    item {
                        WorkspaceOutlinedButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "Device authorization",
                                                vm.pairingCommand(pending),
                                            )
                                        )
                                    )
                                }
                            }
                        ) {
                            Text("Copy authorization command")
                        }
                        WorkspaceButton(onClick = vm::finishPairing) { Text("Check authorization") }
                    }
                }
                state.error?.let { item { ErrorNotice(it) } }
            }
        } else
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (session.hasOlder)
                    item(key = "older") {
                        TextButton(onClick = vm::older, enabled = !session.loadingOlder) {
                            Text(if (session.loadingOlder) "Loading…" else "Load earlier messages")
                        }
                    }
                items(session.messages, key = { it.id }, contentType = { it.role }) { message ->
                    MessageCard(message)
                }
                if (session.messages.isEmpty())
                    item {
                        Text(
                            if (session.connection == "Connected")
                                "Send a message to continue this session."
                            else "Loading session…"
                        )
                    }
                state.workspace.approvals
                    .filter { it.request_session_id == target.session_id }
                    .forEach { approval ->
                        item(key = "approval:${approval.approval_id}") {
                            ApprovalCard(approval, state.working) { vm.decide(approval, it) }
                        }
                    }
            }
    }
}

@Composable
private fun MessageCard(message: ChatItem) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val collapsible = message.role in setOf("tool", "thought") || message.text.length > 12000
    val shown = if (collapsible && !expanded) message.text.take(600) else message.text
    Surface(
        color =
            if (message.role == "user") MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(if (message.role == "user") 12.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (message.role in setOf("tool", "thought", "approval")) {
                Text(
                    message.title.ifBlank {
                        if (message.role == "thought") "Thinking" else "Activity"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (message.status.isNotBlank())
                Text(
                    message.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            SelectionContainer { MarkdownText(shown) }
            if (collapsible)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Collapse" else "Expand")
                }
        }
    }
}

private data class TextBlock(val text: String, val code: Boolean)

@Composable
private fun MarkdownText(text: String) {
    val blocks by
        produceState(listOf(TextBlock(text, false)), text) {
            value =
                withContext(Dispatchers.Default) {
                    text.split("```").mapIndexed { index, value ->
                        TextBlock(
                            if (index % 2 == 1) value.substringAfter('\n', value) else value,
                            index % 2 == 1,
                        )
                    }
                }
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            if (block.code)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        block.text,
                        Modifier.padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            else Text(block.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorNotice(message: String) {
    Text(
        message,
        Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
