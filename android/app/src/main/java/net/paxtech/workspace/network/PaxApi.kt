package net.paxtech.workspace.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class ApiFailure(val status: Int, message: String) : IOException(message)

data class ServerEvent(val id: String, val data: String)

/** No redirects or automatic mutation retries: a lost response is an unknown outcome. */
@Singleton
class PaxApi internal constructor(private val client: OkHttpClient) {
    @Inject
    constructor() :
        this(
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )

    private val streaming =
        client
            .newBuilder()
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

    fun url(
        origin: String,
        segments: List<String>,
        query: Map<String, String> = emptyMap(),
    ): HttpUrl {
        val base = origin.toHttpUrl()
        require(
            base.username.isEmpty() &&
                base.password.isEmpty() &&
                (base.encodedPath == "/" ||
                    (base.isHttps &&
                        base.host == "paxworkspace.net" &&
                        base.encodedPath in
                            setOf(
                                "/api/v1/native/us/",
                                "/api/v1/native/hk/",
                                "/api/v1/native/client/",
                            ))) &&
                base.query == null &&
                base.fragment == null
        )
        require(base.isHttps || base.host in setOf("localhost", "127.0.0.1", "::1")) {
            "HTTPS is required"
        }
        return base
            .newBuilder()
            .apply {
                val routed =
                    if (base.encodedPath != "/") {
                        require(segments.take(2) == listOf("api", "v1")) {
                            "Invalid native API route"
                        }
                        segments.drop(2)
                    } else segments
                routed.forEach { addPathSegment(it) }
                query.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()
    }

    fun request(
        origin: String,
        segments: List<String>,
        key: String = "",
        method: String = "GET",
        body: JsonObject? = null,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): Request =
        Request.Builder()
            .url(url(origin, segments, query))
            .header("Accept", "application/json")
            .apply {
                if (key.isNotBlank()) header("Authorization", "Bearer $key")
                headers.forEach { (k, v) -> header(k, v) }
                method(
                    method,
                    if (method in setOf("GET", "HEAD")) null
                    else (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()),
                )
            }
            .build()

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, r, _ -> r.close() }
                }
            }
        )
    }

    suspend fun json(request: Request): JsonElement =
        withContext(Dispatchers.IO) {
            await(client.newCall(request)).use { response ->
                validate(response, "application/json")
                val source = response.body?.source() ?: throw ApiFailure(502, "Empty response")
                if (source.request(8L * 1024 * 1024 + 1))
                    throw ApiFailure(502, "Response is too large")
                val envelope = wireJson.parseToJsonElement(source.readUtf8()).jsonObject
                if (envelope.number("code") >= 400)
                    throw ApiFailure(
                        envelope.number("code").toInt(),
                        envelope.text("message", "Request failed"),
                    )
                envelope["data"] ?: envelope
            }
        }

    fun events(request: Request): Flow<ServerEvent> =
        callbackFlow {
                val call =
                    streaming.newCall(
                        request.newBuilder().header("Accept", "text/event-stream").build()
                    )
                val reader =
                    launch(Dispatchers.IO) {
                        try {
                            await(call).use { response ->
                                validate(response, "text/event-stream")
                                val source =
                                    response.body?.source()
                                        ?: throw ApiFailure(502, "Empty event stream")
                                val parser = SseParser()
                                while (isActive && !source.exhausted()) {
                                    val line =
                                        try {
                                            source.readUtf8LineStrict(1024L * 1024)
                                        } catch (e: java.io.EOFException) {
                                            if (source.buffer.size <= 1024L * 1024) break
                                            else throw e
                                        }
                                    parser.line(line)?.let { send(it) }
                                }
                                // An incomplete final event must be replayed, never partially
                                // committed.
                            }
                            close()
                        } catch (e: Exception) {
                            close(e)
                        }
                    }
                awaitClose {
                    call.cancel()
                    reader.cancel()
                }
            }
            .buffer(64)

    /** Signed file URLs never inherit API credentials or follow redirects to another origin. */
    internal suspend fun downloadAttachment(url: String, limit: Int): Pair<ByteArray, String> =
        withContext(Dispatchers.IO) {
            val target = url.toHttpUrl()
            require(target.isHttps && target.username.isBlank() && target.password.isBlank()) {
                "HTTPS is required for attachments"
            }
            val request = Request.Builder().url(target).build()
            await(client.newCall(request)).use { response ->
                if (!response.isSuccessful)
                    throw ApiFailure(
                        response.code,
                        "File download failed (${response.code}). Reload the file to try again.",
                    )
                val body = response.body ?: throw IOException("The file response is empty.")
                require(body.contentLength() <= limit) {
                    "This file is larger than the 25 MB mobile download limit."
                }
                val source = body.source()
                require(!source.request(limit.toLong() + 1)) {
                    "This file is larger than the 25 MB mobile download limit."
                }
                source.readByteArray() to
                    (body.contentType()?.let { "${it.type}/${it.subtype}" }
                        ?: "application/octet-stream")
            }
        }

    private fun responseErrorMessage(response: Response): String? {
        if (response.header("Content-Type")?.contains("application/json") != true) return null
        return runCatching {
                // Error bodies may come from gateways. Never render HTML or an unbounded response.
                val raw = response.peekBody(8193).string()
                if (raw.length > 8192) return null
                val body = wireJson.parseToJsonElement(raw).jsonObject
                val detail = body.text("message").ifBlank { body.text("error") }.trim()
                if (detail == "session has no native ACP session id")
                    "This session was not initialized on the agent. Create a new session to continue."
                else
                    detail.takeIf {
                        it.isNotBlank() && it.length <= 512 && it.none { c -> c.isISOControl() }
                    }
            }
            .getOrNull()
    }

    private fun validate(response: Response, type: String) {
        if (!response.isSuccessful) {
            val detail = responseErrorMessage(response)
            val message =
                when (response.code) {
                    301,
                    302,
                    303,
                    307,
                    308 -> "The API gateway requires a different sign-in route."
                    404 -> "The requested resource or service is not available."
                    503 -> "The mobile service is temporarily unavailable."
                    401 -> "Your access has expired or was revoked. Sign in again."
                    403 -> "Access was denied by the server or gateway."
                    429 -> "Too many requests. Please wait before retrying."
                    else ->
                        detail?.let { "$it (${response.code})" }
                            ?: "Server request failed (${response.code})."
                }
            throw ApiFailure(response.code, message)
        }
        if (response.header("Content-Type")?.contains(type) != true)
            throw ApiFailure(502, "Unexpected server response; expected $type")
    }
}

class SseParser {
    private var id = ""
    private val data = StringBuilder()

    fun line(line: String): ServerEvent? {
        if (line.isEmpty()) {
            val event =
                if (data.isNotEmpty()) ServerEvent(id, data.toString().removeSuffix("\n")) else null
            data.clear()
            return event
        }
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (line.substringBefore(':')) {
            "id" -> if (!value.contains('\u0000')) id = value
            "data" -> {
                require(data.length + value.length < 4 * 1024 * 1024) { "Event is too large" }
                data.append(value).append('\n')
            }
        }
        return null
    }
}

fun userPath(vararg parts: String) = listOf("api", "v1", "user", "self") + parts
