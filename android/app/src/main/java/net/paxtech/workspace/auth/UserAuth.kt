package net.paxtech.workspace.auth

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** AuthKit public-client PKCE. Only the rotating refresh token is persisted. */
@Singleton
class UserAuth @Inject constructor(private val api: PaxApi, private val vault: Vault) {
    private val mutex = Mutex()
    private var access = ""
    private var expiresAt = 0L
    internal var providerOrigin = "https://api.workos.com"
    internal var gatewayOrigin = "https://paxworkspace.net"
    internal var apiOrigin = API_ORIGIN

    companion object {
        const val CLIENT_ID = "client_01M4SSKPMAX8DV2RBWMTTT6SR4"
        const val REDIRECT = "https://paxworkspace.net/auth/callback"
        const val API_ORIGIN = "https://paxworkspace.net/api/v1/native/client/"

        private fun random() =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

        internal fun challenge(verifier: String) =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    MessageDigest.getInstance("SHA-256")
                        .digest(verifier.toByteArray(Charsets.US_ASCII))
                )
    }

    suspend fun start(): OAuthPending =
        mutex.withLock {
            if (vault.read().userSession == null) migrateLegacyLocked()
            val state = "native.android." + random()
            val verifier = random()
            val url =
                "$providerOrigin/user_management/authorize"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("client_id", CLIENT_ID)
                    .addQueryParameter("provider", "authkit")
                    .addQueryParameter("response_type", "code")
                    .addQueryParameter("redirect_uri", REDIRECT)
                    .addQueryParameter("state", state)
                    .addQueryParameter("code_challenge", challenge(verifier))
                    .addQueryParameter("code_challenge_method", "S256")
                    .build()
                    .toString()
            val pending = OAuthPending(state, verifier, url, System.currentTimeMillis() + 600_000)
            vault.update { it.copy(oauth = pending, login = null) }
            pending
        }

    suspend fun finish(callback: String): Credential? =
        mutex.withLock {
            require(callback.length <= 8192) { "Invalid sign-in callback" }
            val uri = URI(callback)
            require(
                uri.scheme == "net.paxtech.workspace" &&
                    uri.host == "auth" &&
                    uri.path == "/callback" &&
                    uri.userInfo == null &&
                    uri.port == -1 &&
                    uri.fragment == null
            ) {
                "Invalid sign-in callback"
            }
            val query =
                (uri.rawQuery ?: "").split('&').map {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                        URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                }
            require(query.map { it.first }.distinct().size == query.size) {
                "Invalid sign-in callback"
            }
            val params = query.toMap()
            val pending =
                vault.read().oauth ?: error("Sign-in already completed or expired. Start again.")
            require(
                pending.expiresAt > System.currentTimeMillis() && params["state"] == pending.state
            ) {
                "Sign-in expired or belongs to another request. Start again."
            }
            if (params.containsKey("error")) {
                vault.update { it.copy(oauth = null) }
                error("Sign-in was not completed. Please try again.")
            }
            val code =
                params["code"]?.takeIf { it.isNotBlank() && it.length <= 4096 }
                    ?: error("Missing sign-in code")
            // Consume before exchanging: duplicate callbacks must never exchange a code twice.
            vault.update { it.copy(oauth = null) }
            saveTokens(
                token(
                    body(
                        "grant_type" to "authorization_code",
                        "client_id" to CLIENT_ID,
                        "code" to code,
                        "code_verifier" to pending.verifier,
                    )
                ),
                null,
            )
            bootstrapLocked(null)
        }

    suspend fun restore(): Credential? =
        mutex.withLock {
            val saved = vault.read()
            if (saved.userSession == null) {
                migrateLegacyLocked()
                return@withLock null
            }
            refreshLocked()
            bootstrapLocked(null)
        }

    private suspend fun migrateLegacyLocked() {
        val saved = vault.read()
        saved.credential
            ?.takeIf { it.apiKey.startsWith("paxu_") }
            ?.let { old ->
                try {
                    api.json(
                        api.request(
                            old.origin,
                            userPath("api-keys", old.keyId),
                            old.apiKey,
                            "DELETE",
                        )
                    )
                } catch (e: ApiFailure) {
                    if (e.status !in setOf(401, 404)) throw e
                }
            }
        vault.update { it.copy(credential = null, login = null) }
        access = ""
        expiresAt = 0
    }

    suspend fun bootstrap(region: String? = null): Credential? =
        mutex.withLock {
            require(region == null || region in setOf("us", "hk"))
            refreshLocked()
            bootstrapLocked(region)
        }

    private suspend fun bootstrapLocked(region: String?): Credential? {
        val data =
            api.json(
                    api.request(
                        gatewayOrigin,
                        listOf("api", "v1", "native", "client", "bootstrap"),
                        access,
                        "POST",
                        if (region == null) body() else body("preferred_region" to region),
                    )
                )
                .jsonObject
        if (data.text("status") == "selection_required") return null
        check(
            data.text("status") == "ready" &&
                data.text("api_url") == apiOrigin &&
                data.text("region") in setOf("us", "hk") &&
                data.text("user_id").startsWith("usr_")
        ) {
            "Invalid account response"
        }
        val credential =
            Credential(
                apiOrigin,
                data.text("user_id"),
                data.text("email"),
                "",
                "",
                data.text("region"),
            )
        vault.update { it.copy(credential = credential, login = null) }
        return credential.copy(apiKey = access)
    }

    suspend fun credential(): Credential =
        mutex.withLock {
            refreshLocked()
            val saved = vault.read().credential ?: error("Choose your account region to continue")
            check(saved.origin == apiOrigin)
            saved.copy(apiKey = access)
        }

    private suspend fun refreshLocked() {
        val saved = vault.read().userSession ?: error("Sign in to continue")
        if (access.isNotEmpty() && expiresAt > System.currentTimeMillis() + 60_000) return
        try {
            saveTokens(
                token(
                    body(
                        "grant_type" to "refresh_token",
                        "client_id" to CLIENT_ID,
                        "refresh_token" to saved.refreshToken,
                    )
                ),
                saved.subject,
            )
        } catch (e: ApiFailure) {
            if (e.status in setOf(400, 401, 403)) {
                access = ""
                vault.update { it.copy(userSession = null, credential = null, oauth = null) }
            }
            throw e
        }
    }

    private suspend fun token(body: JsonObject) =
        api.json(
                api.request(
                    providerOrigin,
                    listOf("user_management", "authenticate"),
                    method = "POST",
                    body = body,
                )
            )
            .jsonObject

    private suspend fun saveTokens(data: JsonObject, expectedSubject: String?) {
        val token = data.text("access_token")
        val refresh = data.text("refresh_token")
        val subject = data.obj("user").text("id")
        check(
            token.isNotBlank() &&
                refresh.isNotBlank() &&
                subject.startsWith("user_") &&
                (expectedSubject == null || subject == expectedSubject)
        ) {
            "Invalid sign-in response"
        }
        // The server verifies signatures. Local expiry decoding only schedules refresh.
        val claims =
            wireJson
                .parseToJsonElement(String(Base64.getUrlDecoder().decode(token.split('.')[1])))
                .jsonObject
        check(
            claims.text("sub") == subject &&
                claims.number("exp") * 1000 > System.currentTimeMillis()
        ) {
            "Invalid sign-in response"
        }
        vault.update {
            it.copy(
                userSession = UserSession(subject, refresh),
                credential = if (expectedSubject == null) null else it.credential,
            )
        }
        access = token
        expiresAt = claims.number("exp") * 1000
    }

    suspend fun cancel() =
        mutex.withLock {
            vault.update { it.copy(oauth = null) }
            Unit
        }

    suspend fun signOut() =
        mutex.withLock {
            if (vault.read().userSession != null) {
                try {
                    refreshLocked()
                    api.json(
                        api.request(
                            gatewayOrigin,
                            listOf("api", "v1", "native", "client", "logout"),
                            access,
                            "POST",
                            body(),
                        )
                    )
                } catch (e: ApiFailure) {
                    if (e.status !in setOf(400, 401, 403)) throw e
                }
            }
            access = ""
            expiresAt = 0
            vault.update { VaultData() }
        }
}
