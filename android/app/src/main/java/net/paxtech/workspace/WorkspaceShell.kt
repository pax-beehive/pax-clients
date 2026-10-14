package net.paxtech.workspace

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.paxtech.workspace.data.*
import net.paxtech.workspace.session.*

private val tabs = listOf("Home", "Approvals", "Settings")

@Composable
fun WorkspaceShell(vm: WorkspaceViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(lifecycle, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true)
                vm.setForeground(false)
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.setForeground(true)
        onDispose {
            lifecycle.removeObserver(observer)
            if (activity?.isChangingConfigurations != true) vm.setForeground(false)
        }
    }
    var welcome by rememberSaveable { mutableStateOf(true) }
    var revealWorkspace by rememberSaveable { mutableStateOf(false) }
    // Resume an in-flight browser sign-in directly, including after process recreation.
    LaunchedEffect(state.login, state.needsRegion) {
        if (state.login != null || state.needsRegion) welcome = false
    }
    if (welcome && state.login == null && !state.needsRegion) {
        WelcomeScreen(
            signedIn = state.credential != null,
            restoring = state.loading && state.credential == null,
            onEnter = {
                revealWorkspace = state.credential != null
                welcome = false
            },
        )
        return
    }
    if (state.credential == null || state.login != null) {
        BackHandler(enabled = state.login == null && !state.working) { welcome = true }
        LoginScreen(state, vm)
        return
    }
    BackHandler(enabled = state.selected != null) { vm.close() }
    val workspaceOpacity = remember { Animatable(if (revealWorkspace) 0f else 1f) }
    LaunchedEffect(Unit) {
        workspaceOpacity.animateTo(1f, tween(220))
        revealWorkspace = false
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = workspaceOpacity.value }) {
            AdaptiveWorkspace(
                selected = state.selected != null,
                home = { WorkspaceHome(state, vm) },
                detail = {
                    val target = state.selected
                    if (target != null) key(target.session_id) { SessionScreen(target, state, vm) }
                    else
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            WorkspaceEmptyState(
                                "Your workspace, in focus",
                                "Select a session to pick up where you left off.",
                            )
                        }
                },
            )
        }
    }
}

@Composable
private fun LoginScreen(state: WorkspaceState, vm: WorkspaceViewModel) {
    val context = LocalContext.current
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
                    OrbitMark()
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
                    if (state.needsRegion) {
                        Text("Choose where to host your workspace")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            WorkspaceButton(
                                onClick = { vm.chooseRegion("us") },
                                enabled = !state.working,
                            ) {
                                Text("United States")
                            }
                            WorkspaceOutlinedButton(
                                onClick = { vm.chooseRegion("hk") },
                                enabled = !state.working,
                            ) {
                                Text("Hong Kong")
                            }
                        }
                        WorkspaceOutlinedButton(onClick = vm::signOut) {
                            Text("Use another account")
                        }
                    } else if (state.login == null) {
                        WorkspaceButton(
                            onClick = vm::startLogin,
                            enabled = !state.working && !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sign in")
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowForward,
                                null,
                                Modifier.size(18.dp),
                            )
                        }
                        Text(
                            "Use your PaxWorkspace account in the browser.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Finish signing in with your PaxWorkspace account. You will return here automatically."
                        )
                        WorkspaceButton(
                            onClick = { launchBrowser(state.login.url) },
                            enabled = !state.working,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Continue in browser")
                        }
                        WorkspaceOutlinedButton(
                            onClick = vm::cancelLogin,
                            enabled = !state.working,
                        ) {
                            Text("Cancel")
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
    val focusManager = LocalFocusManager.current
    LaunchedEffect(tab) { focusManager.clearFocus() }
    BackHandler(enabled = tab in listOf("Projects", "Devices")) { tab = "Settings" }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (tab == "Home") "Workspace" else tab,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    if (tab in listOf("Projects", "Devices"))
                        WorkspaceIconButton(onClick = { tab = "Settings" }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to settings")
                        }
                },
                actions = {
                    WorkspaceIconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Outlined.Refresh, "Refresh")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
            ) {
                tabs.forEach { label ->
                    NavigationBarItem(
                        selected =
                            tab == label ||
                                (label == "Settings" && tab in listOf("Projects", "Devices")),
                        onClick = { tab = label },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        colors =
                            NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (
                                        label == "Approvals" &&
                                            state.workspace.approvals.isNotEmpty()
                                    )
                                        Badge { Text(state.workspace.approvals.size.toString()) }
                                }
                            ) {
                                Icon(
                                    when (label) {
                                        "Home" -> Icons.Outlined.Home
                                        "Devices" -> WorkspaceSymbols.Computer
                                        "Approvals" -> Icons.Outlined.CheckCircle
                                        "Settings" -> Icons.Outlined.Settings
                                        else -> Icons.AutoMirrored.Outlined.List
                                    },
                                    null,
                                    Modifier.size(22.dp),
                                )
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { ErrorNotice(it) } }
            when (tab) {
                "Home" -> {
                    item {
                        OutlinedTextField(
                            filter,
                            { filter = it },
                            placeholder = { Text("Search sessions") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Search, null, Modifier.size(20.dp))
                            },
                            trailingIcon = {
                                if (filter.isNotEmpty())
                                    IconButton(onClick = { filter = "" }) {
                                        Icon(Icons.Outlined.Close, "Clear search")
                                    }
                            },
                            shape = MaterialTheme.shapes.large,
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor =
                                        MaterialTheme.colorScheme.surfaceContainerLow,
                                    focusedContainerColor =
                                        MaterialTheme.colorScheme.surfaceContainerLow,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                                ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions =
                                KeyboardActions(onSearch = { focusManager.clearFocus() }),
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
                            TextButton(onClick = { tab = "Projects" }) {
                                Text("Projects")
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowForward,
                                    null,
                                    Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    if (project.isNotEmpty())
                        item {
                            WorkspaceOutlinedButton(onClick = { project = "" }) {
                                Text("Show all projects")
                            }
                        }
                    val shown =
                        state.workspace.sessions.filter {
                            (project.isEmpty() || it.primary_project_id == project) &&
                                (filter.isBlank() || it.title.contains(filter, true))
                        }
                    items(shown, key = { it.session_id }) { target ->
                        SessionRow(
                            target,
                            state.selected?.session_id == target.session_id,
                            state.workspace.projects
                                .firstOrNull { it.project_id == target.primary_project_id }
                                ?.display_name,
                            onClick = {
                                focusManager.clearFocus()
                                vm.open(target)
                            },
                        )
                    }
                    if (shown.isEmpty() && !state.loading)
                        item {
                            WorkspaceEmptyState(
                                if (filter.isNotBlank() || project.isNotEmpty())
                                    "No matching sessions"
                                else "Ready when you are",
                                if (filter.isNotBlank() || project.isNotEmpty())
                                    "Try another search or choose a different project."
                                else
                                    "Start a session in your workspace, then refresh to continue it here.",
                                icon = Icons.Outlined.Search,
                            )
                        }
                    if (state.workspace.hasMore)
                        item {
                            WorkspaceOutlinedButton(
                                onClick = { vm.refresh(true) },
                                enabled = !state.loading,
                            ) {
                                Text("Load more sessions")
                            }
                        }
                }
                "Projects" -> {
                    item { SectionLabel("YOUR PROJECTS") }
                    items(state.workspace.projects, key = { it.project_id }) { item ->
                        SettingsRow(
                            item.display_name,
                            "View sessions",
                            Icons.AutoMirrored.Outlined.List,
                        ) {
                            project = item.project_id
                            tab = "Home"
                        }
                    }
                    if (state.workspace.projects.isEmpty() && !state.loading)
                        item {
                            WorkspaceEmptyState(
                                "No projects yet",
                                "Your workspace projects will appear here.",
                            )
                        }
                }
                "Devices" -> {
                    item { SectionLabel("AGENT COMPUTERS") }
                    items(state.workspace.nodes, key = { it.node_id }) { item ->
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(18.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    WorkspaceSymbols.Computer,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        item.name.ifBlank {
                                            item.hostname.ifBlank { "Connected device" }
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    StatusLabel(
                                        listOf(item.os, if (item.online) "Online" else "Offline")
                                            .filter { it.isNotBlank() }
                                            .joinToString(" · "),
                                        item.online,
                                    )
                                }
                            }
                        }
                    }
                    if (state.workspace.nodes.isEmpty() && !state.loading)
                        item {
                            WorkspaceEmptyState(
                                "No connected devices yet",
                                "Computers running your agents will appear here.",
                                icon = WorkspaceSymbols.Computer,
                            )
                        }
                }
                "Approvals" -> {
                    if (state.workspace.approvals.isNotEmpty())
                        item { SectionLabel("NEEDS YOUR ATTENTION") }
                    items(state.workspace.approvals, key = { it.approval_id }) { approval ->
                        ApprovalCard(approval, state.working) { option ->
                            vm.decide(approval, option)
                        }
                    }
                    if (state.workspace.approvals.isEmpty() && !state.loading)
                        item {
                            WorkspaceEmptyState(
                                "You're all caught up",
                                "No pending approvals.",
                                icon = Icons.Outlined.CheckCircle,
                            )
                        }
                }
                "Settings" -> {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(22.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ) {
                                    Text(
                                        state.credential!!.email.take(1).uppercase(),
                                        Modifier.padding(horizontal = 17.dp, vertical = 12.dp),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                }
                                Text(
                                    state.credential!!.email,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Account region · ${state.credential.region.uppercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item { SectionLabel("WORKSPACE") }
                    item {
                        SettingsRow(
                            "Devices",
                            "Computers running your agents",
                            WorkspaceSymbols.Computer,
                        ) {
                            tab = "Devices"
                        }
                    }
                    item {
                        SettingsRow("Projects", "Browse your workspace", WorkspaceSymbols.Folder) {
                            tab = "Projects"
                        }
                    }
                    item { SectionLabel("ACCOUNT") }
                    item {
                        SettingsRow(
                            "Sign out of this device",
                            "Your agent sessions will keep running",
                            WorkspaceSymbols.SignOut,
                        ) {
                            signOut = true
                        }
                    }
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OrbitMark(Modifier.size(36.dp))
                            Text(
                                "PaxWorkspace for Android",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Version ${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                WorkspaceButton(
                    onClick = {
                        signOut = false
                        vm.signOut()
                    }
                ) {
                    Text("Sign out")
                }
            },
            dismissButton = {
                WorkspaceOutlinedButton(onClick = { signOut = false }) { Text("Cancel") }
            },
        )
}

@Composable
private fun ApprovalCard(
    approval: Approval,
    working: Boolean,
    onDecision: (ApprovalOption) -> Unit,
) {
    var selected by remember { mutableStateOf<ApprovalOption?>(null) }
    Card(
        Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusLabel(approval.status.ifBlank { "pending" }.replaceFirstChar { it.uppercase() })
            Text(approval.title, style = MaterialTheme.typography.titleMedium)
            if (approval.description.isNotBlank())
                Text(
                    approval.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                WorkspaceButton(
                    onClick = {
                        selected = null
                        onDecision(option)
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                WorkspaceOutlinedButton(onClick = { selected = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionScreen(target: AgentSession, state: WorkspaceState, vm: WorkspaceViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    SendMotionHost { motion ->
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
                            StatusLabel(session.connection, session.connection == "Connected")
                        }
                    },
                    navigationIcon = {
                        WorkspaceIconButton(onClick = vm::close) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        WorkspaceIconButton(onClick = vm::reconnect) {
                            Icon(Icons.Outlined.Refresh, "Reconnect")
                        }
                    },
                )
            },
            bottomBar = {
                if (!session.locked)
                    MessageComposer(
                        connection = session.connection,
                        busy = session.busy,
                        error = session.error,
                        onSend = vm::send,
                        onStop = vm::stop,
                        motion = motion,
                    )
            },
        ) { padding ->
            if (session.locked) {
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Text(
                            "Enable encrypted session access",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            "You are signed in. This encrypted session also requires approval of this client’s encryption key on the agent’s computer."
                        )
                    }
                    item {
                        WorkspaceButton(onClick = vm::beginPairing) { Text("Request key access") }
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
                                                    "Encryption key approval",
                                                    vm.pairingCommand(pending),
                                                )
                                            )
                                        )
                                    }
                                }
                            ) {
                                Text("Copy key approval command")
                            }
                            WorkspaceButton(onClick = vm::finishPairing) {
                                Text("Check key access")
                            }
                        }
                    }
                    state.error?.let { item { ErrorNotice(it) } }
                }
            } else
                ConversationTimeline(
                    messages = session.messages,
                    connection = session.connection,
                    hasOlder = session.hasOlder,
                    loadingOlder = session.loadingOlder,
                    onOlder = vm::older,
                    motion = motion,
                    waitingForReply = session.busy && session.messages.lastOrNull()?.role == "user",
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    approvals =
                        state.workspace.approvals.filter {
                            it.request_session_id == target.session_id
                        },
                    approval = { approval ->
                        ApprovalCard(approval, state.working) { vm.decide(approval, it) }
                    },
                )
        }
    }
}

@Composable
internal fun ErrorNotice(message: String) {
    Text(
        message,
        Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
