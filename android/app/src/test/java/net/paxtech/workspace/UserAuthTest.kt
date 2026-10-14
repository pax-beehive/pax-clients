package net.paxtech.workspace

import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.paxtech.workspace.auth.UserAuth
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class UserAuthTest {
    @Test
    fun doesNotLoseLegacyKeyWhenMigrationMustBeRetried() = runBlocking {
        MockWebServer().use { server ->
            val legacy =
                Credential(
                    server.url("/").toString(),
                    "usr_fixture",
                    "f@example.invalid",
                    "paxu_legacy",
                    "key_legacy",
                    "us",
                )
            val vault = MemoryVault(VaultData(credential = legacy))
            val auth = auth(server, vault)
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching { auth.start() }.isFailure)
            assertEquals(legacy, vault.value.credential)
            assertNull(vault.value.oauth)
            server.enqueue(response(body()))
            auth.start()
            assertNull(vault.value.credential)
            assertNotNull(vault.value.oauth)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun clearsLocalKeysWhenSigningOutAnAlreadyRevokedSession() = runBlocking {
        MockWebServer().use { server ->
            val vault =
                MemoryVault(
                    VaultData(
                        userSession = UserSession("user_fixture", "revoked"),
                        keys = listOf(RootKey("usr_fixture", "agent_fixture", 1, "fixture")),
                    )
                )
            val auth = auth(server, vault)
            server.enqueue(MockResponse().setResponseCode(400))
            auth.signOut()
            assertEquals(VaultData(), vault.value)
        }
    }

    private fun response(value: JsonObject) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())

    private fun tokens(subject: String = "user_fixture", refresh: String = "rotated") =
        buildJsonObject {
            val claims =
                body(
                    "sub" to subject,
                    "exp" to (System.currentTimeMillis() / 1000 + 300).toString(),
                )
            put(
                "access_token",
                "e30." +
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(claims.toString().toByteArray()) +
                    ".signature",
            )
            put("refresh_token", refresh)
            put("user", body("id" to subject))
        }

    private fun ready() =
        body(
            "status" to "ready",
            "user_id" to "usr_fixture",
            "email" to "fixture@example.invalid",
            "region" to "hk",
            "api_url" to UserAuth.API_ORIGIN,
        )

    private fun auth(server: MockWebServer, vault: MemoryVault) =
        UserAuth(PaxApi(), vault).apply {
            providerOrigin = server.url("/").toString().removeSuffix("/")
            gatewayOrigin = providerOrigin
        }

    @Test
    fun usesStandardPkceVectorAndUniquePersistedTransactions() = runBlocking {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            UserAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val vault = MemoryVault()
        val auth = UserAuth(PaxApi(), vault)
        val first = auth.start()
        val second = auth.start()
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.verifier, second.verifier)
        assertEquals(second, vault.value.oauth)
        val url = second.url.toHttpUrl()
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(UserAuth.challenge(second.verifier), url.queryParameter("code_challenge"))
        assertEquals(UserAuth.REDIRECT, url.queryParameter("redirect_uri"))
        assertNull(url.queryParameter("client_secret"))
        assertNull(url.queryParameter("device_id"))
    }

    @Test
    fun rejectsUnsolicitedTamperedExpiredAndDuplicateCallbacksBeforeNetwork() = runBlocking {
        MockWebServer().use { server ->
            val vault = MemoryVault()
            val auth = auth(server, vault)
            val pending = auth.start()
            for (callback in
                listOf(
                    "net.paxtech.workspace://auth/callback?state=other&code=x",
                    "net.paxtech.workspace://auth/callback?state=${pending.state}&state=${pending.state}&code=x",
                    "https://evil.invalid/callback?state=${pending.state}&code=x",
                )) {
                assertTrue(runCatching { auth.finish(callback) }.isFailure)
            }
            vault.value = vault.value.copy(oauth = pending.copy(expiresAt = 0))
            assertTrue(
                runCatching {
                        auth.finish(
                            "net.paxtech.workspace://auth/callback?state=${pending.state}&code=x"
                        )
                    }
                    .isFailure
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun exchangesOnlyOnceWithVerifierAndKeepsAccessTokenOutOfVault() = runBlocking {
        MockWebServer().use { server ->
            val vault = MemoryVault()
            val auth = auth(server, vault)
            val pending = auth.start()
            server.enqueue(response(tokens()))
            server.enqueue(response(ready()))
            val callback =
                "net.paxtech.workspace://auth/callback?state=${pending.state}&code=single_use"
            val credential = auth.finish(callback)!!
            assertEquals("usr_fixture", credential.userId)
            assertEquals("hk", credential.region)
            assertTrue(credential.apiKey.isNotBlank())
            assertEquals("", vault.value.credential!!.apiKey)
            assertEquals("rotated", vault.value.userSession!!.refreshToken)
            assertNull(vault.value.oauth)
            val exchange =
                wireJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(pending.verifier, exchange.text("code_verifier"))
            assertFalse(exchange.containsKey("client_secret"))
            assertEquals("authorization_code", exchange.text("grant_type"))
            assertEquals("/api/v1/native/client/bootstrap", server.takeRequest().path)
            assertTrue(runCatching { auth.finish(callback) }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun serializesRefreshAcrossConcurrentRequestsAndRotatesBeforeUse() = runBlocking {
        MockWebServer().use { server ->
            val vault =
                MemoryVault(
                    VaultData(
                        credential =
                            Credential(
                                UserAuth.API_ORIGIN,
                                "usr_fixture",
                                "f@example.invalid",
                                "",
                                "",
                                "hk",
                            ),
                        userSession = UserSession("user_fixture", "original"),
                    )
                )
            val auth = auth(server, vault)
            server.enqueue(response(tokens()))
            coroutineScope { (1..12).map { async { auth.credential() } }.awaitAll() }
            assertEquals(1, server.requestCount)
            assertEquals("rotated", vault.value.userSession!!.refreshToken)
            val refresh =
                wireJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("refresh_token", refresh.text("grant_type"))
            assertEquals("original", refresh.text("refresh_token"))
        }
    }

    @Test
    fun rejectsRefreshIdentitySwitchAndClearsRevokedSession() = runBlocking {
        MockWebServer().use { server ->
            val vault =
                MemoryVault(VaultData(userSession = UserSession("user_fixture", "original")))
            val auth = auth(server, vault)
            server.enqueue(response(tokens("user_other")))
            assertTrue(runCatching { auth.credential() }.isFailure)
            assertEquals("original", vault.value.userSession!!.refreshToken)
            server.enqueue(MockResponse().setResponseCode(400))
            assertTrue(runCatching { auth.credential() }.isFailure)
            assertNull(vault.value.userSession)
        }
    }

    @Test
    fun migratesLegacyKeyWithoutRegisteringAnyNodeAndPreservesEncryptionKeys() = runBlocking {
        MockWebServer().use { server ->
            val root = RootKey("usr_fixture", "agent_fixture", 1, "fixture")
            val vault =
                MemoryVault(
                    VaultData(
                        credential =
                            Credential(
                                server.url("/").toString(),
                                "usr_fixture",
                                "f@example.invalid",
                                "paxu_legacy",
                                "key_legacy",
                                "us",
                            ),
                        keys = listOf(root),
                    )
                )
            val auth = auth(server, vault)
            server.enqueue(response(body()))
            assertNull(auth.restore())
            assertNull(vault.value.credential)
            assertEquals(listOf(root), vault.value.keys)
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/v1/user/self/api-keys/key_legacy", request.path)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun neverFollowsAuthenticationRedirectsOrAutomaticallyRetries() = runBlocking {
        MockWebServer().use { server ->
            val vault =
                MemoryVault(VaultData(userSession = UserSession("user_fixture", "original")))
            val auth = auth(server, vault)
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://evil.invalid/steal")
            )
            val error = runCatching { auth.credential() }.exceptionOrNull()
            assertEquals(302, (error as ApiFailure).status)
            assertEquals(1, server.requestCount)
            assertEquals("original", vault.value.userSession!!.refreshToken)
        }
    }
}
