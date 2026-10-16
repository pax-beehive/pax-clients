package net.paxtech.workspace.session

import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import net.paxtech.workspace.crypto.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.*

data class SessionState(
    val messages: List<ChatItem> = emptyList(),
    val connection: String = "Connecting",
    val busy: Boolean = false,
    val error: String? = null,
    val locked: Boolean = false,
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
)

class SessionRuntime
@Inject
constructor(private val repo: WorkspaceRepository, private val pairing: DevicePairing) {
    private val mutable = MutableStateFlow(SessionState())
    val state = mutable.asStateFlow()
    private val timeline = Timeline()
    private val mutex = Mutex()
    private var session: AgentSession? = null
    private var root: RootKey? = null
    private var cursor = 0L
    private var before = 0L
    private var requestId: String? = null
    private var pendingRpc: CompletableDeferred<JsonObject>? = null
    private var pendingRpcId: String? = null
    private var nativeId = ""
    private var dirty = false
    private val sending = MutableStateFlow(false)
    private var observerGeneration = 0L

    private fun route(suffix: String) =
        userPath("agents", session!!.agent_id, "sessions", session!!.session_id, suffix)

    private suspend fun request(
        path: List<String>,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: JsonObject? = null,
        headers: Map<String, String> = emptyMap(),
    ): okhttp3.Request {
        val credential = repo.credential()
        return repo.api.request(
            credential.origin,
            path,
            credential.apiKey,
            method,
            body,
            query,
            headers,
        )
    }

    suspend fun run(target: AgentSession) = coroutineScope {
        require(target.transport in setOf("manager", "e2ee")) { "Unsupported session transport" }
        session = target
        root = if (target.encrypted) pairing.key(target.agent_id) else null
        nativeId = target.native_id
        requestId = null
        pendingRpc = null
        pendingRpcId = null
        mutex.withLock {
            timeline.clear()
            cursor = 0
            before = 0
            dirty = false
            mutable.value = SessionState(locked = target.encrypted && root == null)
        }
        if (target.encrypted && root == null) {
            mutable.update { it.copy(connection = "Encryption key approval required") }
            return@coroutineScope
        }
        val publisher = launch {
            while (isActive) {
                delay(50)
                mutex.withLock { publish() }
            }
        }
        try {
            if (target.encrypted) encryptedHistory(false) else history(false)
            mutable.update {
                it.copy(
                    connection = "Connected",
                    busy = target.runtime_status in setOf("running", "waiting_approval"),
                )
            }
            if (target.encrypted) observe(target)
            else
                sending.collectLatest { active ->
                    if (!active && mutable.value.connection != "Needs reconnect") observe(target)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update {
                it.copy(connection = "Unavailable", error = e.message ?: "Session unavailable")
            }
        } finally {
            publisher.cancel()
            mutex.withLock { publish() }
            pendingRpc?.cancel()
            mutable.update {
                it.copy(
                    connection = if (it.connection == "Unavailable") "Unavailable" else "Paused",
                    busy = false,
                )
            }
        }
    }

    private suspend fun observe(target: AgentSession) {
        var backoff = 500L
        while (currentCoroutineContext().isActive) {
            try {
                if (target.encrypted) encryptedEvents() else plainEvents()
                backoff = 500
                delay(if (target.encrypted) 500 else 3000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (
                    e !is IOException ||
                        (e is ApiFailure && e.status !in setOf(408, 429, 500, 502, 503, 504))
                )
                    throw e
                mutable.update {
                    it.copy(
                        connection = "Reconnecting",
                        error = "Connection interrupted; recovering session.",
                    )
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(10000)
                if (!target.encrypted) history(false)
            }
        }
    }

    private fun publish() {
        if (dirty) {
            mutable.update { it.copy(messages = timeline.snapshot()) }
            dirty = false
        }
    }

    private suspend fun history(older: Boolean) {
        val query = mutableMapOf("limit" to "100", "view" to "full")
        if (older && before > 0) query["before_seq"] = before.toString()
        val page =
            repo.api
                .json(request(userPath("sessions", session!!.session_id, "history"), query))
                .jsonObject
        val messages =
            page.array("messages").map { it.jsonObject }.sortedBy { it.number("session_seq") }
        mutex.withLock {
            if (older) {
                val prefix = Timeline()
                messages.forEach(prefix::history)
                timeline.prepend(prefix)
            } else {
                timeline.clear()
                messages.forEach(timeline::history)
            }
            val pagination = page.obj("pagination")
            before =
                pagination.number(
                    "next_before_seq",
                    messages.firstOrNull()?.number("session_seq") ?: 0,
                )
            mutable.update {
                it.copy(
                    hasOlder =
                        (pagination.flag("has_older") || pagination.flag("has_more")) && before > 0
                )
            }
            dirty = true
            publish()
        }
    }

    private suspend fun plainEvents() {
        val generation = mutex.withLock { observerGeneration }
        var turn = ""
        val canonical = linkedMapOf<String, JsonObject>()
        repo.api.events(request(route("events"))).collect { event ->
            val data = wireJson.parseToJsonElement(event.data).jsonObject
            require(data.text("session_id", session!!.session_id) == session!!.session_id) {
                "Session event route mismatch"
            }
            mutex.withLock {
                // collectLatest cancels the observer on send. Also guard a frame already queued
                // for this lock, so it cannot replace the POST stream with a durable snapshot.
                if (sending.value || generation != observerGeneration) return@withLock
                when (data.text("type")) {
                    "turn_start" -> {
                        turn = data.text("turn_id")
                        canonical.clear()
                        mutable.update { it.copy(busy = true) }
                    }
                    "history_item" -> {
                        val item = data.obj("item")
                        require(
                            item.text("session_id", session!!.session_id) == session!!.session_id
                        )
                        canonical[item.text("message_id")] = item
                    }
                    "history_remove" -> {
                        canonical.remove(data.text("message_id"))
                        timeline.remove(data.text("message_id"))
                    }
                    "head" ->
                        if (turn.isNotEmpty())
                            timeline.replaceTurn(
                                turn,
                                canonical.values.sortedBy { it.number("session_seq") },
                            )
                    "acp" -> timeline.frame(data.obj("frame"), data.text("turn_id", turn))
                    "turn_done",
                    "no_running_turn" -> mutable.update { it.copy(busy = false) }
                    "error" -> throw ApiFailure(422, data.text("message", "Session failed"))
                    "buffer_miss",
                    "resync" -> throw IOException("Session replay required")
                }
                dirty = true
                mutable.update {
                    if (it.connection == "Needs reconnect") it
                    else it.copy(connection = "Connected", error = null)
                }
            }
        }
    }

    private fun batch(envelope: Envelope): JsonObject {
        val target = session!!
        val key = root!!
        require(
            envelope.agent_id == target.agent_id &&
                envelope.session_id == target.session_id &&
                envelope.key_epoch == key.epoch &&
                envelope.kind == "acp_event"
        ) {
            "Encrypted event route mismatch"
        }
        val data =
            wireJson
                .parseToJsonElement(
                    String(
                        EnvelopeCrypto.open(decode64(key.encoded), "event", envelope),
                        Charsets.UTF_8,
                    )
                )
                .jsonObject
        require(data["frames"] is JsonArray) { "Encrypted event frames missing" }
        return data
    }

    private suspend fun encryptedHistory(older: Boolean) {
        val collected = Timeline()
        var after = 0L
        var head: Long? = null
        var turn = ""
        var start = 0L
        var hasOlder = false
        do {
            val query = mutableMapOf("view" to "replay", "limit" to "100")
            if (head != null) {
                query["through_cursor"] = "$head"
                query["turn_ref"] = turn
                query["after_cursor"] = "$after"
            } else if (older && before > 0) query["before_turn"] = "$before"
            val page = repo.api.json(request(route("encrypted-events"), query)).jsonObject
            val nextHead = page.number("head_cursor", -1)
            val nextTurn = page.text("turn_ref")
            val nextStart = page.number("turn_start_cursor", -1)
            require(nextHead >= 0 && nextStart >= 0)
            if (head != null)
                require(head == nextHead && turn == nextTurn && start == nextStart) {
                    "Replay snapshot changed"
                }
            else {
                head = nextHead
                turn = nextTurn
                start = nextStart
                hasOlder = page.flag("has_older")
            }
            var latest = after
            page.array("events").forEach { stored ->
                val e = stored.jsonObject
                val next = e.number("cursor", -1)
                require(next > latest && next <= nextHead) { "Replay cursor did not advance" }
                val value = batch(wireJson.decodeFromJsonElement(e.getValue("envelope")))
                if (turn.isNotBlank())
                    require(value.text("turn_id") == turn) { "Encrypted turn identity mismatch" }
                value.array("frames").forEach {
                    collected.frame(it.jsonObject, value.text("turn_id"))
                }
                latest = next
            }
            require(
                page.number("next_after_cursor", -1) == latest &&
                    (!page.flag("has_more") || latest > after)
            )
            after = latest
        } while (page.flag("has_more"))
        mutex.withLock {
            if (older) timeline.prepend(collected)
            else {
                timeline.replaceWith(collected)
                cursor = head!!
            }
            before = start
            mutable.update { it.copy(hasOlder = hasOlder && start > 0) }
            dirty = true
            publish()
        }
    }

    private suspend fun encryptedEvents() {
        repo.api
            .events(
                request(
                    route("encrypted-events"),
                    headers = if (cursor > 0) mapOf("Last-Event-ID" to "$cursor") else emptyMap(),
                )
            )
            .collect { event ->
                val next = event.id.toLongOrNull() ?: error("Invalid encrypted event cursor")
                if (next <= cursor) return@collect
                val value = batch(wireJson.decodeFromString(event.data))
                mutex.withLock {
                    value.array("frames").forEach { entry ->
                        val frame = entry.jsonObject
                        timeline.frame(frame, value.text("turn_id"))
                        if (
                            frame.obj("result").text("stopReason").isNotBlank() ||
                                frame.obj("result").text("stop_reason").isNotBlank()
                        ) {
                            mutable.update { it.copy(busy = false) }
                        }
                        if (
                            frame.text("id") == pendingRpcId &&
                                (frame.containsKey("result") || frame.containsKey("error"))
                        )
                            pendingRpc?.complete(frame)
                        if (
                            frame.text("id") == requestId &&
                                (frame.containsKey("result") || frame.containsKey("error"))
                        ) {
                            mutable.update {
                                it.copy(
                                    busy = false,
                                    error = frame.obj("error").text("message").ifBlank { null },
                                )
                            }
                            requestId = null
                        }
                    }
                    cursor = next
                    dirty = true
                    mutable.update { it.copy(connection = "Connected") }
                }
            }
    }

    suspend fun loadOlder() {
        if (mutable.value.loadingOlder || !mutable.value.hasOlder) return
        mutable.update { it.copy(loadingOlder = true) }
        try {
            if (session!!.encrypted) encryptedHistory(true) else history(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message) }
        } finally {
            mutable.update { it.copy(loadingOlder = false) }
        }
    }

    suspend fun send(text: String, clientRequestId: String = "prompt_${UUID.randomUUID()}") {
        require(text.isNotBlank())
        check(
            mutable.value.connection == "Connected" && !mutable.value.busy && !mutable.value.locked
        )
        check(sending.compareAndSet(false, true)) { "A send is already in progress" }
        mutable.update { it.copy(busy = true, error = null) }
        val promptId = clientRequestId
        val promptTurn = "local:$promptId"
        try {
            mutex.withLock {
                // Fence queued observer frames even if a very short send is conflated by StateFlow.
                if (!session!!.encrypted) observerGeneration++
                timeline.prompt(promptId, promptTurn, text, "Sending…")
                dirty = true
                publish()
            }
            if (session!!.encrypted) {
                if (nativeId.isBlank()) {
                    val cwd =
                        session!!.workspace_roots.firstOrNull()
                            ?: error("Session working directory is unavailable")
                    val id = "new_${UUID.randomUUID()}"
                    val response = CompletableDeferred<JsonObject>()
                    pendingRpc = response
                    pendingRpcId = id
                    encryptedCommand(
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", id)
                            put("method", "session/new")
                            putJsonObject("params") {
                                put("cwd", cwd)
                                putJsonArray("mcpServers") {}
                            }
                        }
                    )
                    val result = withTimeout(30000) { response.await() }
                    check(!result.containsKey("error")) {
                        result.obj("error").text("message", "Session initialization failed")
                    }
                    nativeId =
                        result
                            .obj("result")
                            .text("sessionId", result.obj("result").text("session_id"))
                    check(nativeId.isNotBlank()) { "Missing native session identity" }
                    pendingRpc = null
                    pendingRpcId = null
                }
                val id = promptId
                requestId = id
                encryptedCommand(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("method", "session/prompt")
                        putJsonObject("params") {
                            put("sessionId", nativeId)
                            putJsonArray("prompt") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                }
                            }
                        }
                    }
                )
            } else {
                var terminal = false
                var liveTurn = promptTurn
                repo.api
                    .events(
                        request(
                            userPath(
                                "nodes",
                                session!!.node_id,
                                "agents",
                                session!!.agent_id,
                                "conversation",
                            ),
                            method = "POST",
                            body = body("session_id" to session!!.session_id, "input" to text),
                        )
                    )
                    .collect { event ->
                        val data = wireJson.parseToJsonElement(event.data).jsonObject
                        require(
                            data.text("session_id", session!!.session_id) == session!!.session_id
                        )
                        mutex.withLock {
                            when (data.text("type")) {
                                "turn_started" -> {
                                    liveTurn =
                                        data.text("turn_id").also { require(it.isNotBlank()) }
                                    timeline.prompt(promptId, liveTurn, text, "")
                                }
                                "acp",
                                "approval_required" -> {
                                    val turn = data.text("turn_id", liveTurn)
                                    require(turn == liveTurn) {
                                        "Conversation turn identity mismatch"
                                    }
                                    val frame = data.obj("frame")
                                    // Our prompt is already visible. Do not append its server echo.
                                    val update =
                                        frame.obj("params").obj("update").ifEmpty {
                                            frame.obj("update")
                                        }
                                    if (
                                        frame.text("method") != "session/prompt" &&
                                            update.text("sessionUpdate") != "user_message_chunk"
                                    ) {
                                        timeline.frame(frame, turn)
                                    }
                                }
                                "error" ->
                                    throw ApiFailure(
                                        422,
                                        data.text("message", "Agent request failed"),
                                    )
                                "done",
                                "turn_done",
                                "interrupted" -> terminal = true
                            }
                            dirty = true
                        }
                    }
                check(terminal) {
                    "Delivery outcome is unknown. Reconnect to inspect the session before sending again."
                }
                history(false)
            }
            mutex.withLock {
                timeline.promptStatus(promptId, "")
                dirty = true
                publish()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutex.withLock {
                timeline.promptStatus(promptId, "Delivery unconfirmed")
                dirty = true
                publish()
            }
            mutable.update {
                it.copy(
                    error = e.message ?: "Delivery outcome unknown",
                    busy = false,
                    connection = "Needs reconnect",
                )
            }
        } finally {
            if (!session!!.encrypted) mutable.update { it.copy(busy = false) }
            // Restart a fresh observer only after the final history replacement; never let a
            // stale in-flight history batch overwrite the live response or render a duplicate.
            sending.value = false
        }
    }

    private suspend fun encryptedCommand(frame: JsonObject) {
        val s = session!!
        val key = root!!
        val id = "cmd_${UUID.randomUUID()}"
        val encrypted =
            EnvelopeCrypto.seal(
                decode64(key.encoded),
                "command",
                s.agent_id,
                s.session_id,
                key.epoch,
                id,
                "acp_command",
                frame.toString().toByteArray(),
            )
        val response =
            repo.api
                .json(
                    request(
                        route("encrypted-commands"),
                        method = "POST",
                        body = wireJson.encodeToJsonElement(encrypted).jsonObject,
                    )
                )
                .jsonObject
        require(response.text("command_id") == id) { "Command receipt identity mismatch" }
    }

    suspend fun stop() {
        if (session!!.encrypted) {
            check(nativeId.isNotBlank()) { "Native session identity is unavailable" }
            encryptedCommand(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "session/cancel")
                    putJsonObject("params") { put("sessionId", nativeId) }
                }
            )
        } else
            repo.api.json(
                request(
                    route("turn") + "stop",
                    method = "POST",
                    body = body("reason" to "user_requested"),
                    headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
                )
            )
        mutable.update {
            it.copy(
                connection = "Connected",
                error = "Stop requested; waiting for agent confirmation.",
            )
        }
    }
}
