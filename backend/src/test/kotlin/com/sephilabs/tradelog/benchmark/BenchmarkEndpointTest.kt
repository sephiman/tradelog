// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.benchmark

import com.sephilabs.tradelog.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.LocalDate

/**
 * Exercises the benchmark endpoints over real HTTP: they are market reference data, so they are not
 * profile-scoped, but they must still be behind authentication. Also pins the `keys` query
 * parameter's comma-separated form, which is what the frontend sends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BenchmarkEndpointTest @Autowired constructor(
    private val prices: BenchmarkPriceRepository,
) {
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

    private fun registerSession(): Session {
        val s = Session()
        s.client.send(HttpRequest.newBuilder(url("/api/auth/csrf")).GET().build(), BodyHandlers.discarding())
        val email = "bench${System.nanoTime()}@example.com"
        val r = s.client.send(
            HttpRequest.newBuilder(url("/api/auth/register"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", s.xsrf())
                .POST(BodyPublishers.ofString("""{"email":"$email","password":"password123","locale":"en"}""")).build(),
            BodyHandlers.ofString(),
        )
        assertThat(r.statusCode()).isEqualTo(201)
        return s
    }

    private fun get(s: Session?, path: String) =
        (s?.client ?: HttpClient.newHttpClient())
            .send(HttpRequest.newBuilder(url(path)).GET().build(), BodyHandlers.ofString())

    @Test
    fun `benchmark reference data requires authentication`() {
        assertThat(get(null, "/api/benchmarks").statusCode()).isEqualTo(401)
        assertThat(get(null, "/api/benchmarks/daily?from=2026-03-01&to=2026-03-02").statusCode()).isEqualTo(401)
        assertThat(get(registerSession(), "/api/benchmarks").statusCode()).isEqualTo(200)
    }

    @Test
    fun `daily closes bind a comma-separated keys parameter and cover every day asked for`() {
        prices.save(BenchmarkPrice(benchmarkKey = "gold", priceDate = LocalDate.parse("2026-03-02"), close = BigDecimal("100")))
        prices.save(BenchmarkPrice(benchmarkKey = "sp500", priceDate = LocalDate.parse("2026-03-02"), close = BigDecimal("200")))
        prices.flush()

        val body = get(registerSession(), "/api/benchmarks/daily?from=2026-03-02&to=2026-03-04&keys=gold,sp500").body()

        // Exactly the two requested series, not all five.
        assertThat(Regex("\"key\":\"([a-z_0-9]+)\"").findAll(body).map { it.groupValues[1] }.toList())
            .containsExactly("sp500", "gold") // registry order, not the order the caller listed
        // Three calendar days each, the market-closed ones carried from the 2nd.
        assertThat(Regex("\"date\":\"2026-03-0[234]\"").findAll(body).count()).isEqualTo(6)
        assertThat(body).contains("\"close\":\"100")
        assertThat(body).contains("\"close\":\"200")
    }

    @Test
    fun `a malformed date is rejected rather than silently coerced`() {
        assertThat(get(registerSession(), "/api/benchmarks/daily?from=nope&to=2026-03-02").statusCode())
            .isEqualTo(400)
    }
}
