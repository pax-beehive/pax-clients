package net.paxtech.workspace.auth

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.*
import okhttp3.HttpUrl.Companion.toHttpUrl

@Singleton
class DeviceLogin @Inject constructor(private val api: PaxApi, private val vault: Vault) {
    suspend fun start(): LoginAttempt = coroutineScope {
        val saved = vault.read()
        saved.login
            ?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?.let {
                return@coroutineScope it
            }
        val origins = listOf("https://api.paxworkspace.net", "https://hk-api.paxworkspace.net")
        val candidates =
            origins
                .map { origin ->
                    async {
                        try {
                            val result =
                                api.json(
                                        api.request(
                                            origin,
                                            listOf("api", "v1", "paxl", "device-login", "start"),
                                            method = "POST",
                                            body =
                                                body(
                                                    "client_name" to "PaxWorkspace Android",
                                                    "protocol" to "client_commit_v1",
                                                ),
                                        )
                                    )
                                    .jsonObject
                            require(result.text("protocol") == "client_commit_v1") {
                                "Server does not support safe device login"
                            }
                            val region = result.text("region")
                            require(region == if (origin == origins.first()) "us" else "hk") {
                                "Unexpected login region"
                            }
                            val verification = result.text("verification_uri").toHttpUrl()
                            require(
                                verification.isHttps &&
                                    verification.host == "paxworkspace.net" &&
                                    verification.encodedPath == "/paxl-login.html"
                            ) {
                                "Untrusted sign-in destination"
                            }
                            LoginCandidate(
                                    origin,
                                    region,
                                    result.text("login_id"),
                                    result.text("user_code"),
                                    result.text("poll_token"),
                                    result.number("interval", 2).coerceIn(2, 30),
                                )
                                .also {
                                    require(
                                        it.loginId.isNotBlank() &&
                                            it.pollToken.isNotBlank() &&
                                            Regex("[A-Z0-9]{6}").matches(it.code)
                                    )
                                }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        check(candidates.isNotEmpty()) {
            "Could not reach the sign-in service. Check your connection and retry."
        }
        val url =
            "https://paxworkspace.net/paxl-login.html"
                .toHttpUrl()
                .newBuilder()
                .apply { candidates.forEach { addQueryParameter(it.region + "_code", it.code) } }
                .build()
                .toString()
        val attempt = LoginAttempt(candidates, url, System.currentTimeMillis() + 9 * 60 * 1000)
        vault.update { it.copy(login = attempt) }
        attempt
    }

    suspend fun finish(): Credential {
        var state = vault.read()
        var attempt = state.login ?: error("Start sign-in first")
        while (attempt.selected == null) {
            check(System.currentTimeMillis() < attempt.expiresAt) {
                "Sign-in expired. Cancel and start again."
            }
            for (candidate in attempt.candidates) {
                val poll =
                    try {
                        action(candidate)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiFailure) {
                        if (e.status >= 500 || e.status == 429) continue else throw e
                    }
                if (poll.text("status") == "confirmed") {
                    val user = poll.obj("user")
                    check(
                        poll.text("region") == candidate.region &&
                            user.text("user_id").isNotBlank() &&
                            user.text("email").isNotBlank()
                    ) {
                        "Sign-in identity mismatch"
                    }
                    attempt =
                        attempt.copy(
                            selected = candidate,
                            userId = user.text("user_id"),
                            email = user.text("email"),
                        )
                    vault.update { it.copy(login = attempt) }
                    break
                }
            }
            if (attempt.selected == null) delay(attempt.candidates.maxOf { it.interval } * 1000)
        }
        val selected = requireNotNull(attempt.selected)
        state = vault.read()
        var credential =
            state.credential?.takeIf { it.userId == attempt.userId && it.origin == selected.origin }
        if (credential == null) {
            val committed = action(selected, "commit", attempt.userId)
            val user = committed.obj("user")
            check(
                committed.text("status") == "approved" &&
                    committed.text("region") == selected.region &&
                    user.text("user_id") == attempt.userId &&
                    user.text("email") == attempt.email
            ) {
                "Issued credential does not match the confirmed account"
            }
            credential =
                Credential(
                    selected.origin,
                    attempt.userId,
                    attempt.email,
                    committed.text("api_key"),
                    committed.obj("api_key_meta").text("key_id"),
                    selected.region,
                )
            check(credential.apiKey.isNotBlank() && credential.keyId.isNotBlank()) {
                "Server did not issue a credential"
            }
            vault.update { it.copy(credential = credential) }
        }
        check(action(selected, "ack", attempt.userId).text("status") == "consumed") {
            "Sign-in receipt pending. Retry to finish securely."
        }
        for (other in attempt.candidates.filter { it != selected }) {
            try {
                action(other, "cancel")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        vault.update { it.copy(login = null) }
        return credential
    }

    suspend fun cancel() {
        val attempt = vault.read().login
        if (vault.read().credential != null) signOut()
        attempt?.candidates?.forEach { candidate ->
            try {
                action(candidate, "cancel")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        vault.update { it.copy(login = null) }
    }

    private suspend fun action(
        candidate: LoginCandidate,
        action: String = "",
        userId: String = "",
    ) =
        api.json(
                api.request(
                    candidate.origin,
                    listOf("api", "v1", "paxl", "device-login", "poll"),
                    method = "POST",
                    body =
                        body(
                            "login_id" to candidate.loginId,
                            "poll_token" to candidate.pollToken,
                            "action" to action,
                            "expected_user_id" to userId,
                        ),
                )
            )
            .jsonObject

    suspend fun signOut() {
        val c = vault.read().credential ?: return
        try {
            api.json(api.request(c.origin, userPath("api-keys", c.keyId), c.apiKey, "DELETE"))
        } catch (e: ApiFailure) {
            if (e.status !in setOf(401, 404)) throw e
        }
        vault.update { VaultData() }
    }
}
