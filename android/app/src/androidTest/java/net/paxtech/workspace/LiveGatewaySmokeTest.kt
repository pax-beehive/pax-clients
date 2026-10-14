package net.paxtech.workspace

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.paxtech.workspace.auth.UserAuth
import net.paxtech.workspace.network.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in read-only boundary checks; never opens the application vault. */
class LiveGatewaySmokeTest {
    @Test
    fun rejectsAnonymousAndNodeCredentials(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("paxLiveSmoke") == "true")
        val api = PaxApi()
        for (key in listOf("", "paxu_invalid_smoke_test", "invalid.jwt.signature")) {
            val error =
                runCatching { api.json(api.request(UserAuth.API_ORIGIN, userPath("me"), key)) }
                    .exceptionOrNull()
            assertTrue(error is ApiFailure && error.status == 401)
        }
        for (region in listOf("us", "hk")) {
            val error =
                runCatching {
                        api.json(
                            api.request(
                                "https://paxworkspace.net",
                                listOf(
                                    "api",
                                    "v1",
                                    "native",
                                    region,
                                    "paxl",
                                    "device-login",
                                    "start",
                                ),
                                method = "POST",
                            )
                        )
                    }
                    .exceptionOrNull()
            assertTrue(error is ApiFailure && error.status == 410)
        }
    }
}
