// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.kraken.KrakenSpotConnector
import com.sephilabs.tradelog.position.PositionSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** Kraken spot: trades folded into positions, with its asset naming resolved from the pair listing. */
class KrakenSpotConnectorTest {

    private val connector = KrakenSpotConnector(AppProperties(), ObjectMapper())
    private fun parse(json: String) = ObjectMapper().readTree(json)

    /** Kraken's real `AssetPairs` shape, including the legacy X/Z-prefixed asset codes. */
    private val assetPairs = parse(
        """
        { "error": [], "result": {
          "XXBTZUSD": { "altname": "XBTUSD", "wsname": "XBT/USD", "base": "XXBT", "quote": "ZUSD" },
          "XETHZEUR": { "altname": "ETHEUR", "wsname": "ETH/EUR", "base": "XETH", "quote": "ZEUR" },
          "SOLUSDT":  { "altname": "SOLUSDT", "wsname": "SOL/USDT", "base": "SOL", "quote": "USDT" }
        } }
        """.trimIndent(),
    )

    @Test
    fun `Kraken's legacy asset codes resolve to canonical symbols`() {
        val pairs = connector.mapPairs(assetPairs)

        // Both the pair key and its altname resolve, since trades can report either.
        assertThat(connector.symbolFor("XXBTZUSD", pairs)).isEqualTo(Symbol("BTC", "USD"))
        assertThat(connector.symbolFor("XBTUSD", pairs)).isEqualTo(Symbol("BTC", "USD"))
        // Z-prefixed fiat and X-prefixed crypto both lose the prefix; XBT becomes BTC.
        assertThat(connector.symbolFor("XETHZEUR", pairs)).isEqualTo(Symbol("ETH", "EUR"))
        assertThat(connector.symbolFor("ETHEUR", pairs)).isEqualTo(Symbol("ETH", "EUR"))
        // A modern, unprefixed listing passes through untouched.
        assertThat(connector.symbolFor("SOLUSDT", pairs)).isEqualTo(Symbol("SOL", "USDT"))
    }

    @Test
    fun `an unlisted pair falls back to the shared splitter rather than failing`() {
        // The listing is best-effort public data; a pair missing from it must still map to something
        // sensible rather than dropping the trade.
        assertThat(connector.symbolFor("XBTUSD", emptyMap())).isEqualTo(Symbol("BTC", "USD"))
        assertThat(connector.symbolFor("ETHUSD", emptyMap())).isEqualTo(Symbol("ETH", "USD"))
    }

    @Test
    fun `a missing pair listing yields no map instead of throwing`() {
        assertThat(connector.mapPairs(null)).isEmpty()
        assertThat(connector.mapPairs(parse("""{ "error": ["EGeneral:Unavailable"] }"""))).isEmpty()
    }

    @Test
    fun `a buy then a full sell becomes one long position with real fees`() {
        // `result.trades` is an OBJECT keyed by trade id, not an array — Kraken's own shape.
        val body = """
            { "error": [], "result": { "count": 2, "trades": {
              "TXID1": { "ordertxid": "O1", "pair": "XXBTZUSD", "time": 1700000000.1234, "type": "buy",
                         "ordertype": "limit", "price": "60000.0", "cost": "6000.0", "fee": "9.6",
                         "vol": "0.1", "margin": "0.00000" },
              "TXID2": { "ordertxid": "O2", "pair": "XXBTZUSD", "time": 1700003600.5, "type": "sell",
                         "ordertype": "market", "price": "61000.0", "cost": "6100.0", "fee": "9.76",
                         "vol": "0.1", "margin": "0.00000" }
            } } }
        """.trimIndent()

        val fills = connector.mapTrades(parse(body))
        assertThat(fills).hasSize(2)
        // Fractional epoch seconds keep their sub-second part.
        assertThat(fills[0].ts).isEqualTo(Instant.ofEpochMilli(1_700_000_000_123L))

        val pairs = connector.mapPairs(assetPairs)
        val p = PositionReconstructor.reconstruct(fills) { connector.symbolFor(it, pairs) }.single()
            .let { it.copy(realizedPnl = PositionReconstructor.realizedFromPrices(it)) }

        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USD"))
        assertThat(p.side).isEqualTo(PositionSide.LONG)
        assertThat(p.qty).isEqualByComparingTo("0.1")
        assertThat(p.entryPrice).isEqualByComparingTo("60000")
        assertThat(p.exitPrice).isEqualByComparingTo("61000")
        // (61000 - 60000) * 0.1, with Kraken's real fees — spot does report them.
        assertThat(p.realizedPnl).isEqualByComparingTo("100")
        assertThat(p.fees).isEqualByComparingTo("19.36")
        // No funding exists on spot.
        assertThat(p.funding).isEqualByComparingTo("0")
        assertThat(p.realizedPnl.subtract(p.fees).subtract(p.funding)).isEqualByComparingTo("80.64")
    }

    @Test
    fun `a holding that was never fully sold stays open and is not emitted`() {
        // Buy 1, sell 0.4: still 0.6 long, so there is no closed position yet. Emitting a partial exit
        // as a finished trade would report a profit the user has not actually taken.
        val body = """
            { "error": [], "result": { "trades": {
              "T1": { "pair": "SOLUSDT", "time": 1700000000, "type": "buy",  "price": "100", "vol": "1",   "fee": "0.1" },
              "T2": { "pair": "SOLUSDT", "time": 1700003600, "type": "sell", "price": "120", "vol": "0.4", "fee": "0.05" }
            } } }
        """.trimIndent()

        val pairs = connector.mapPairs(assetPairs)
        val positions = PositionReconstructor.reconstruct(connector.mapTrades(parse(body))) {
            connector.symbolFor(it, pairs)
        }

        assertThat(positions).isEmpty()
    }

    @Test
    fun `unmappable trades are skipped rather than guessed at`() {
        val body = """
            { "error": [], "result": { "trades": {
              "T1": { "pair": "XXBTZUSD", "time": 1700000000, "type": "deposit", "price": "1", "vol": "1" },
              "T2": { "pair": "XXBTZUSD", "type": "buy", "price": "1", "vol": "1" },
              "T3": { "time": 1700000000, "type": "buy", "price": "1", "vol": "1" }
            } } }
        """.trimIndent()

        assertThat(connector.mapTrades(parse(body))).isEmpty()
    }

    @Test
    fun `spot PnL is recorded in the pair's own quote currency, not assumed to be USDT`() {
        val body = """
            { "error": [], "result": { "trades": {
              "T1": { "pair": "XETHZEUR", "time": 1700000000, "type": "buy",  "price": "3000", "vol": "1", "fee": "5" },
              "T2": { "pair": "XETHZEUR", "time": 1700003600, "type": "sell", "price": "3100", "vol": "1", "fee": "5" }
            } } }
        """.trimIndent()

        val pairs = connector.mapPairs(assetPairs)
        val p = PositionReconstructor.reconstruct(connector.mapTrades(parse(body))) {
            connector.symbolFor(it, pairs)
        }.single()

        assertThat(p.symbol).isEqualTo(Symbol("ETH", "EUR"))
        // The connector's own adjust() stamps the currency; asserted here through the symbol it derives
        // from, since a EUR figure sitting in a column labelled USDT would be a silent lie.
        assertThat(p.symbol.quote).isEqualTo("EUR")
    }
}
