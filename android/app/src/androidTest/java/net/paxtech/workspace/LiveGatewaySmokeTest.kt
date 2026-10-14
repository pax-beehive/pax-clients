package net.paxtech.workspace

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.paxtech.workspace.auth.DeviceLogin
import net.paxtech.workspace.data.*
import net.paxtech.workspace.network.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in; never signs in an account or writes the application's vault. */
class LiveGatewaySmokeTest {
    @Test
    fun anonymousBoundaryAndRegionalDeviceLogin(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("paxLiveSmoke") == "true")
        val api = PaxApi()
        val vault =
            object : Vault {
                var value = VaultData()

                override suspend fun read() = value

                override suspend fun update(change: (VaultData) -> VaultData): VaultData {
                    value = change(value)
                    return value
                }
            }
        val auth = DeviceLogin(api, vault)
        try {
            for (region in listOf("us", "hk")) {
                val origin = "https://paxworkspace.net/api/v1/native/$region/"
                for (key in listOf("", "paxu_invalid_smoke_test")) {
                    val denied =
                        runCatching { api.json(api.request(origin, userPath("me"), key)) }
                            .exceptionOrNull()
                    assertTrue(
                        "$region must reject ${if (key.isEmpty()) "anonymous" else "invalid-key"} reads; received ${denied?.javaClass?.simpleName} ${(denied as? ApiFailure)?.status}: ${denied?.message}",
                        // The PostgreSQL Manager maps an unknown key to 404;
                        // anonymous requests are rejected by the gateway with 401.
                        denied is ApiFailure &&
                            denied.status in (if (key.isEmpty()) setOf(401) else setOf(401, 404)),
                    )
                }
            }
            val browser =
                runCatching { api.json(api.request("https://paxworkspace.net", userPath("me"))) }
                    .exceptionOrNull()
            assertTrue(browser is ApiFailure && browser.status == 401)
            val attempt = auth.start()
            assertEquals(setOf("us", "hk"), attempt.candidates.map { it.region }.toSet())
            for (candidate in attempt.candidates) {
                val status =
                    api.json(
                        api.request(
                            candidate.origin,
                            listOf("api", "v1", "paxl", "device-login", "poll"),
                            method = "POST",
                            body =
                                body(
                                    "login_id" to candidate.loginId,
                                    "poll_token" to candidate.pollToken,
                                ),
                        )
                    ) as kotlinx.serialization.json.JsonObject
                assertEquals("pending", status.text("status"))
            }
            Log.i(
                "PaxLiveSmoke",
                "PASS: US/HK start and pending poll; anonymous and invalid-key reads denied; browser auth retained",
            )
        } finally {
            auth.cancel()
        }
    }
}
