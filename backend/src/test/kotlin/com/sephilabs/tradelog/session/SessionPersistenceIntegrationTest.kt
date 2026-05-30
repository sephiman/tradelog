// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.session

import com.sephilabs.tradelog.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

/**
 * End-to-end over real HTTP: a successful auth persists a row in `spring_session` (Spring Session
 * JDBC, not in-memory Tomcat) and the session cookie authenticates subsequent requests. Guards the
 * fix where JDBC sessions weren't engaging (sessions died on restart → 401s).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionPersistenceIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
) {
    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres = IntegrationTestBase.postgres
    }

    @LocalServerPort
    var port: Int = 0

    private fun url(path: String) = URI.create("http://localhost:$port$path")

    @Test
    fun `auth persists a JDBC session and authenticates follow-up requests`() {
        val cm = CookieManager()
        val client = HttpClient.newBuilder().cookieHandler(cm).build()
        val email = "sess${System.nanoTime()}@example.com"
        val before = jdbc.queryForObject("SELECT count(*) FROM spring_session", Long::class.java)!!

        client.send(HttpRequest.newBuilder(url("/api/auth/csrf")).GET().build(), BodyHandlers.discarding())
        val xsrf = cm.cookieStore.cookies.first { it.name == "XSRF-TOKEN" }.value

        val reg = client.send(
            HttpRequest.newBuilder(url("/api/auth/register"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", xsrf)
                .POST(BodyPublishers.ofString("""{"email":"$email","password":"password123","locale":"en"}""")).build(),
            BodyHandlers.ofString(),
        )
        assertThat(reg.statusCode()).isEqualTo(201)
        assertThat(cm.cookieStore.cookies.any { it.name == "SESSION" }).isTrue

        // A session row is now persisted in Postgres (proves JDBC, not in-memory).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Long::class.java)!!).isGreaterThan(before)

        // The session cookie authenticates the next request.
        val me = client.send(HttpRequest.newBuilder(url("/api/auth/me")).GET().build(), BodyHandlers.ofString())
        assertThat(me.statusCode()).isEqualTo(200)
        assertThat(me.body()).contains(email)

        // A client without the cookie is unauthorized.
        val anon = HttpClient.newHttpClient()
        val meAnon = anon.send(HttpRequest.newBuilder(url("/api/auth/me")).GET().build(), BodyHandlers.discarding())
        assertThat(meAnon.statusCode()).isEqualTo(401)
    }
}
