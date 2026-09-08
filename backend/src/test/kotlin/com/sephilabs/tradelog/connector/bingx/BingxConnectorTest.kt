// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.bingx

import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.ExchangeCredentials
import com.sephilabs.tradelog.connector.SyncCursor
import com.sephilabs.tradelog.position.PositionSide
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant

/** A stand-in BingX answers fills by `startTs` and positions verbatim, so paging and anchoring are observed end to end. */
class BingxConnectorTest {

    private val creds = ExchangeCredentials("key", "secret")
    private val start = Instant.parse("2026-09-01T00:00:00Z")
    private val end = Instant.parse("2026-09-08T00:00:00Z")

    private lateinit var server: HttpServer
    private val fillRequests = mutableListOf<Map<String, String>>()
    private var fillsAnswer: (startTs: Long, endTs: Long) -> List<String> = { _, _ -> emptyList() }
    private var positionsAnswer: () -> String = { """{"code":0,"msg":"","data":[]}""" }

    @BeforeEach
    fun startVenue() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/openApi/swap/v2/trade/allFillOrders") { exchange ->
            val query = exchange.requestURI.rawQuery.split('&').associate { pair ->
                val (k, v) = pair.split('=', limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }
            fillRequests += query
            val rows = fillsAnswer(query.getValue("startTs").toLong(), query.getValue("endTs").toLong()).joinToString(",")
            respond(exchange, """{"code":0,"msg":"","data":{"fill_orders":[$rows]}}""")
        }
        server.createContext("/openApi/swap/v2/user/positions") { exchange -> respond(exchange, positionsAnswer()) }
        server.start()
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @AfterEach
    fun stopVenue() = server.stop(0)

    private fun connector(): BingxConnector {
        val endpoint = AppProperties.ExchangeEndpoint("http://127.0.0.1:${server.address.port}")
        return BingxConnector(AppProperties(connectors = AppProperties.Connectors(bingx = endpoint)), ObjectMapper())
    }

    private fun at(second: Long): Instant = start.plusSeconds(second)

    private fun fill(ts: Instant, side: String = "BUY", price: String = "100", qty: String = "1", positionSide: String = "LONG"): String {
        val amount = price.toBigDecimal().multiply(qty.toBigDecimal()).toPlainString()
        return """{"filledTm":"$ts","symbol":"BTC-USDT","side":"$side","positionSide":"$positionSide",
            "price":"$price","amount":"$amount","volume":"$qty","commission":"-0.01"}"""
    }

    private fun fill(second: Long, price: String = "100"): String = fill(at(second), price = price)

    /** 1ms before the fill at [second], where a re-request must start since `startTs` is exclusive. */
    private fun resumeFrom(second: Long): Long = at(second).toEpochMilli() - 1

    // ---------------------------------------------------------------------------------------------
    // Paging past the 512-row reply cap
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `re-requests a window from its last fill until nothing new arrives`() {
        fillsAnswer = { startTs, _ ->
            when (startTs) {
                start.toEpochMilli() -> listOf(fill(1), fill(2), fill(3))
                resumeFrom(3) -> listOf(fill(3), fill(4), fill(5))
                resumeFrom(5) -> listOf(fill(5))
                else -> error("unexpected startTs $startTs")
            }
        }

        val rows = connector().fetchFillRows(creds, start, end)

        assertThat(rows.map { Instant.parse(it["filledTm"].asText()) }).containsExactly(at(1), at(2), at(3), at(4), at(5))
        assertThat(fillRequests).hasSize(3)
        assertThat(fillRequests.map { it["endTs"] }).containsOnly(end.toEpochMilli().toString())
    }

    @Test
    fun `keeps every fill of a same-second burst that straddles two answers`() {
        // Three fills share second 3; the first answer is cut after two of them.
        fillsAnswer = { startTs, _ ->
            when (startTs) {
                start.toEpochMilli() -> listOf(fill(1), fill(3, "101"), fill(3, "102"))
                resumeFrom(3) -> listOf(fill(3, "101"), fill(3, "102"), fill(3, "103"), fill(4))
                resumeFrom(4) -> listOf(fill(4))
                else -> error("unexpected startTs $startTs")
            }
        }

        val rows = connector().fetchFillRows(creds, start, end)

        assertThat(rows.map { it["price"].asText() }).containsExactly("100", "101", "102", "103", "100")
        assertThat(fillRequests).hasSize(3)
    }

    @Test
    fun `an empty window costs a single request`() {
        val rows = connector().fetchFillRows(creds, start, end)

        assertThat(rows).isEmpty()
        assertThat(fillRequests).hasSize(1)
    }

    // ---------------------------------------------------------------------------------------------
    // Anchoring the reconstruction on the open positions the venue reports
    // ---------------------------------------------------------------------------------------------

    /** Fills at fixed instants, served to whichever window (exclusive start, inclusive end) they fall in. */
    private fun serveFills(vararg fills: Pair<Instant, String>) {
        fillsAnswer = { startTs, endTs ->
            fills.filter { (ts, _) -> ts.toEpochMilli() > startTs && ts.toEpochMilli() <= endTs }.map { it.second }
        }
    }

    @Test
    fun `a one-way history that starts inside a short is anchored on the flat account and yields the later round trip`() {
        val now = Instant.now()
        val cover = now.minus(Duration.ofDays(6))
        val open = now.minus(Duration.ofDays(3))
        val close = now.minus(Duration.ofDays(2))
        serveFills(
            cover to fill(cover, "BUY", "100", "0.1131", "BOTH"), // covers a short opened before the history
            open to fill(open, "BUY", "100", "0.05", "BOTH"),
            close to fill(close, "SELL", "110", "0.05", "BOTH"),
        )
        positionsAnswer = { """{"code":0,"msg":"","data":[]}""" }

        val batch = connector().fetchClosedPositions(creds, SyncCursor(), null)

        val p = batch.records.single()
        assertThat(p.side).isEqualTo(PositionSide.LONG)
        assertThat(p.openedAt).isEqualTo(open)
        assertThat(p.closedAt).isEqualTo(close)
        assertThat(p.qty).isEqualByComparingTo("0.05")
        assertThat(p.realizedPnl).isEqualByComparingTo("0.5")
        assertThat(batch.nextCursor.lastClosedAt).isEqualTo(close)
    }

    @Test
    fun `an open long reported by the venue explains the surplus buys instead of hiding the closed trade`() {
        val now = Instant.now()
        val open = now.minus(Duration.ofDays(5))
        val close = now.minus(Duration.ofDays(4))
        val stillOpen = now.minus(Duration.ofDays(1))
        serveFills(
            open to fill(open, "BUY", "100", "1", "BOTH"),
            close to fill(close, "SELL", "120", "1", "BOTH"),
            stillOpen to fill(stillOpen, "BUY", "90", "2", "BOTH"),
        )
        positionsAnswer = {
            """{"code":0,"msg":"","data":[{"symbol":"BTC-USDT","positionSide":"LONG","positionAmt":"2.0000",
                "avgPrice":"90","leverage":5,"unrealizedProfit":"0","realisedProfit":"0"}]}"""
        }

        val records = connector().fetchClosedPositions(creds, SyncCursor(), null).records

        assertThat(records).hasSize(1)
        assertThat(records.single().realizedPnl).isEqualByComparingTo("20")
    }

    @Test
    fun `a positions endpoint the key may not read leaves the sync working on the flat-start assumption`() {
        val now = Instant.now()
        val open = now.minus(Duration.ofDays(3))
        val close = now.minus(Duration.ofDays(2))
        serveFills(open to fill(open, "BUY", "100", "1", "BOTH"), close to fill(close, "SELL", "110", "1", "BOTH"))
        positionsAnswer = { """{"code":100403,"msg":"permission denied","data":null}""" }

        val records = connector().fetchClosedPositions(creds, SyncCursor(), null).records

        assertThat(records).hasSize(1)
    }
}
