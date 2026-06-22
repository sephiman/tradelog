// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.bitmart

import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.PositionReconstructor
import com.sephilabs.tradelog.connector.Symbol
import com.sephilabs.tradelog.position.PositionSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Maps a representative `/contract/private/trades` payload (the shape verified against BitMart's docs
 * and SDK) through the connector's field mapping and the shared reconstructor, asserting side decoding,
 * contract-size scaling of `vol`, directly-reported PnL/fees, and millisecond `create_time` parsing.
 */
class BitmartConnectorTest {

    private val connector = BitmartConnector(AppProperties(), ObjectMapper())
    private val normalize: (String) -> Symbol = connector::normalizeSymbol

    // BTCUSDT trades 0.001 BTC per contract; ETHUSDT 0.01 ETH per contract.
    private val sizes = mapOf("BTCUSDT" to BigDecimal("0.001"), "ETHUSDT" to BigDecimal("0.01"))

    private fun parse(json: String) = ObjectMapper().readTree(json)

    @Test
    fun `long open then close — vol scaled by contract size, pnl and fees from payload`() {
        // side 1 = buy_open_long, side 3 = sell_close_long. vol in contracts.
        val body = """
            { "code": 1000, "message": "Ok", "data": [
              { "symbol": "BTCUSDT", "side": 1, "price": "100", "vol": "10",
                "paid_fees": "0.5", "realised_profit": "0", "create_time": 1700000000000 },
              { "symbol": "BTCUSDT", "side": 3, "price": "110", "vol": "10",
                "paid_fees": "0.5", "realised_profit": "10", "create_time": 1700000100000 }
            ] }
        """.trimIndent()

        val fills = connector.mapTrades(parse(body), sizes)
        assertEquals(2, fills.size)
        assertEquals(true, fills[0].buy)
        assertEquals(false, fills[1].buy)
        // vol 10 contracts * 0.001 BTC = 0.01 BTC
        assertEquals(0, fills[0].qty.compareTo(BigDecimal("0.01")))
        // create_time is milliseconds
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), fills[0].ts)

        val p = PositionReconstructor.reconstruct(fills, normalize).single()
        assertEquals(Symbol("BTC", "USDT"), p.symbol)
        assertEquals(PositionSide.LONG, p.side)
        assertEquals(0, p.qty.compareTo(BigDecimal("0.01")))
        assertEquals(0, p.entryPrice.compareTo(BigDecimal("100")))
        assertEquals(0, p.exitPrice.compareTo(BigDecimal("110")))
        // PnL/fees come straight from the payload (summed), not derived from prices.
        assertEquals(0, p.realizedPnl.compareTo(BigDecimal("10")))
        assertEquals(0, p.fees.compareTo(BigDecimal("1.0")))
    }

    @Test
    fun `short open then close via side codes 4 and 2`() {
        // side 4 = sell_open_short, side 2 = buy_close_short.
        val body = """
            { "code": 1000, "data": [
              { "symbol": "ETHUSDT", "side": 4, "price": "50", "vol": "5",
                "paid_fees": "0.2", "realised_profit": "0", "create_time": 1700000000000 },
              { "symbol": "ETHUSDT", "side": 2, "price": "45", "vol": "5",
                "paid_fees": "0.2", "realised_profit": "2.5", "create_time": 1700000050000 }
            ] }
        """.trimIndent()

        val p = PositionReconstructor.reconstruct(connector.mapTrades(parse(body), sizes), normalize).single()
        assertEquals(PositionSide.SHORT, p.side)
        // vol 5 * 0.01 = 0.05 ETH
        assertEquals(0, p.qty.compareTo(BigDecimal("0.05")))
        assertEquals(0, p.entryPrice.compareTo(BigDecimal("50")))
        assertEquals(0, p.exitPrice.compareTo(BigDecimal("45")))
        assertEquals(0, p.realizedPnl.compareTo(BigDecimal("2.5")))
    }

    @Test
    fun `unknown symbol falls back to contract size 1 and still reconstructs`() {
        val body = """
            { "code": 1000, "data": [
              { "symbol": "FOOUSDT", "side": 1, "price": "2", "vol": "3",
                "paid_fees": "0", "realised_profit": "0", "create_time": 1700000000000 },
              { "symbol": "FOOUSDT", "side": 3, "price": "3", "vol": "3",
                "paid_fees": "0", "realised_profit": "3", "create_time": 1700000010000 }
            ] }
        """.trimIndent()

        val p = PositionReconstructor.reconstruct(connector.mapTrades(parse(body), emptyMap()), normalize).single()
        // No contract size known => vol used as-is.
        assertEquals(0, p.qty.compareTo(BigDecimal("3")))
        assertEquals(PositionSide.LONG, p.side)
    }
}
