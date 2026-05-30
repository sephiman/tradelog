// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.profile

import com.sephilabs.tradelog.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

/**
 * Exercises the profile-ownership boundary over real HTTP: a profile-scoped endpoint is reachable
 * only by the owning user (others get 403, anonymous gets 401), enforced by ProfileAccessInterceptor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProfileAccessInterceptorTest {
    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres = IntegrationTestBase.postgres
    }

    @LocalServerPort
    var port: Int = 0

    private fun url(p: String) = URI.create("http://localhost:$port$p")

    private class Session {
        val cm = CookieManager()
        val client: HttpClient = HttpClient.newBuilder().cookieHandler(cm).build()
        fun xsrf() = cm.cookieStore.cookies.first { it.name == "XSRF-TOKEN" }.value
    }

    /** Registers a fresh user (auto-logs-in) and returns an authenticated Session. */
    private fun registerSession(): Session {
        val s = Session()
        s.client.send(HttpRequest.newBuilder(url("/api/auth/csrf")).GET().build(), BodyHandlers.discarding())
        val email = "iam${System.nanoTime()}@example.com"
        val r = s.client.send(
            HttpRequest.newBuilder(url("/api/auth/register"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", s.xsrf())
                .POST(BodyPublishers.ofString("""{"email":"$email","password":"password123","locale":"en"}""")).build(),
            BodyHandlers.ofString(),
        )
        assertThat(r.statusCode()).isEqualTo(201)
        return s
    }

    private fun status(s: Session?, path: String): Int {
        val client = s?.client ?: HttpClient.newHttpClient()
        return client.send(HttpRequest.newBuilder(url(path)).GET().build(), BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `profile-scoped endpoint is reachable only by its owner`() {
        val owner = registerSession()
        val other = registerSession()

        // Owner creates a profile.
        val create = owner.client.send(
            HttpRequest.newBuilder(url("/api/profiles"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", owner.xsrf())
                .POST(BodyPublishers.ofString("""{"kind":"PERSONAL","name":"Mine${System.nanoTime()}"}""")).build(),
            BodyHandlers.ofString(),
        )
        assertThat(create.statusCode()).isEqualTo(201)
        val profileId = Regex(""""id"\s*:\s*"([^"]+)"""").find(create.body())!!.groupValues[1]

        assertThat(status(owner, "/api/profiles/$profileId/positions")).isEqualTo(200)   // owner
        assertThat(status(other, "/api/profiles/$profileId/positions")).isEqualTo(403)   // not the owner
        assertThat(status(null, "/api/profiles/$profileId/positions")).isEqualTo(401)    // anonymous
        assertThat(status(owner, "/api/profiles/not-a-uuid/positions")).isEqualTo(400)   // malformed id
    }
}
