// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.binance.BinanceConnector
import com.sephilabs.tradelog.connector.bingx.BingxConnector
import com.sephilabs.tradelog.connector.bybit.BybitConnector
import com.sephilabs.tradelog.connector.hyperliquid.HyperliquidConnector
import com.sephilabs.tradelog.connector.kraken.KrakenFuturesConnector
import com.sephilabs.tradelog.connector.lighter.LighterConnector
import com.sephilabs.tradelog.position.PositionSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Fill mapping of the reconstruction connectors, folded through the real reconstructor. */
class FillReconstructionMappingTest {

    private val props = AppProperties()
    private val mapper = ObjectMapper()
    private fun parse(json: String) = ObjectMapper().readTree(json)

    private fun net(r: PositionRecord): BigDecimal = r.realizedPnl.subtract(r.fees).subtract(r.funding)

    // ---------------------------------------------------------------------------------------------
    // Binance — per-fill realizedPnl and commission, plus funding from the income endpoint.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `Binance folds scaled entries into one position, summing its reported PnL and fees`() {
        val connector = BinanceConnector(props, mapper)
        // Two buys then one sell closing the lot: one flat-to-flat position with a VWAP entry.
        val body = """
            [
              { "symbol": "BTCUSDT", "id": 1, "side": "BUY",  "positionSide": "BOTH", "price": "60000",
                "qty": "0.1", "commission": "0.6", "realizedPnl": "0",  "time": 1700000000000 },
              { "symbol": "BTCUSDT", "id": 2, "side": "BUY",  "positionSide": "BOTH", "price": "62000",
                "qty": "0.1", "commission": "0.62", "realizedPnl": "0", "time": 1700000600000 },
              { "symbol": "BTCUSDT", "id": 3, "side": "SELL", "positionSide": "BOTH", "price": "63000",
                "qty": "0.2", "commission": "1.26", "realizedPnl": "400", "time": 1700003600000 }
            ]
        """.trimIndent()

        val fills = connector.mapTrades(parse(body))
        assertThat(fills).hasSize(3)

        val p = PositionReconstructor.reconstruct(fills, connector::normalizeSymbol).single()
        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USDT"))
        assertThat(p.side).isEqualTo(PositionSide.LONG)
        assertThat(p.qty).isEqualByComparingTo("0.2")
        assertThat(p.entryPrice).isEqualByComparingTo("61000") // volume-weighted across both buys
        assertThat(p.exitPrice).isEqualByComparingTo("63000")
        // Binance reports PnL per fill, so it is summed rather than derived from the prices.
        assertThat(p.realizedPnl).isEqualByComparingTo("400")
        assertThat(p.fees).isEqualByComparingTo("2.48")
        assertThat(net(p)).isEqualByComparingTo("397.52")
    }

    @Test
    fun `Binance keeps a hedge-mode long and short on one symbol apart`() {
        val connector = BinanceConnector(props, mapper)
        // Without separating by positionSide these four fills would net to zero exposure and collapse
        // into a single nonsensical lifecycle instead of two real ones.
        val body = """
            [
              { "symbol": "BTCUSDT", "id": 1, "side": "BUY",  "positionSide": "LONG",  "price": "60000",
                "qty": "0.1", "commission": "0.6", "realizedPnl": "0",   "time": 1700000000000 },
              { "symbol": "BTCUSDT", "id": 2, "side": "SELL", "positionSide": "SHORT", "price": "60000",
                "qty": "0.1", "commission": "0.6", "realizedPnl": "0",   "time": 1700000100000 },
              { "symbol": "BTCUSDT", "id": 3, "side": "SELL", "positionSide": "LONG",  "price": "61000",
                "qty": "0.1", "commission": "0.61", "realizedPnl": "100", "time": 1700003600000 },
              { "symbol": "BTCUSDT", "id": 4, "side": "BUY",  "positionSide": "SHORT", "price": "61000",
                "qty": "0.1", "commission": "0.61", "realizedPnl": "-100", "time": 1700003700000 }
            ]
        """.trimIndent()

        val positions = PositionReconstructor.reconstruct(connector.mapTrades(parse(body)), connector::normalizeSymbol)

        assertThat(positions).hasSize(2)
        assertThat(positions.map { it.side }).containsExactlyInAnyOrder(PositionSide.LONG, PositionSide.SHORT)
        assertThat(positions.single { it.side == PositionSide.LONG }.realizedPnl).isEqualByComparingTo("100")
        assertThat(positions.single { it.side == PositionSide.SHORT }.realizedPnl).isEqualByComparingTo("-100")
        // The grouping key is stripped back off, so both are plain BTC/USDT.
        assertThat(positions.map { it.symbol }).containsOnly(Symbol("BTC", "USDT"))
    }

    // ---------------------------------------------------------------------------------------------
    // Bybit — no per-fill PnL (derived from prices), funding arriving as its own execType.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `Bybit derives PnL from leg prices and absorbs a funding execution`() {
        val connector = BybitConnector(props, mapper)
        val body = """
            { "retCode": 0, "result": { "nextPageCursor": "", "list": [
              { "symbol": "BTCUSDT", "side": "Buy",  "execPrice": "60000", "execQty": "0.1",
                "execFee": "0.6", "execType": "Trade",   "execTime": "1700000000000" },
              { "symbol": "BTCUSDT", "side": "Buy",  "execPrice": "0",     "execQty": "0",
                "execFee": "0.25", "execType": "Funding", "execTime": "1700001800000" },
              { "symbol": "BTCUSDT", "side": "Sell", "execPrice": "61000", "execQty": "0.1",
                "execFee": "0.61", "execType": "Trade",   "execTime": "1700003600000" }
            ] } }
        """.trimIndent()

        val fills = connector.mapExecutions(parse(body))
        assertThat(fills).hasSize(3)

        val p = PositionReconstructor.reconstruct(fills, connector::normalizeSymbol).single()
            // The connector derives PnL from prices after reconstruction; do the same here.
            .let { it.copy(realizedPnl = PositionReconstructor.realizedFromPrices(it)) }

        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USDT"))
        assertThat(p.qty).isEqualByComparingTo("0.1")
        // (61000 - 60000) * 0.1
        assertThat(p.realizedPnl).isEqualByComparingTo("100")
        assertThat(p.fees).isEqualByComparingTo("1.21")
        // The zero-quantity funding row carried no exposure but its money still landed on the position.
        assertThat(p.funding).isEqualByComparingTo("0.25")
        assertThat(net(p)).isEqualByComparingTo("98.54")
    }

    @Test
    fun `Bybit ignores executions that move no exposure`() {
        val connector = BybitConnector(props, mapper)
        val body = """
            { "retCode": 0, "result": { "list": [
              { "symbol": "BTCUSDT", "side": "Buy", "execPrice": "60000", "execQty": "0.1",
                "execFee": "0.6", "execType": "Settle", "execTime": "1700000000000" }
            ] } }
        """.trimIndent()

        assertThat(connector.mapExecutions(parse(body))).isEmpty()
    }

    // ---------------------------------------------------------------------------------------------
    // Kraken Futures — linear perpetuals only, ISO timestamps, and no usable fee data.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `Kraken Futures reconstructs a linear perpetual and records no fee`() {
        val connector = KrakenFuturesConnector(props, mapper)
        val body = """
            { "result": "success", "serverTime": "2026-08-01T00:00:00.000Z", "fills": [
              { "fill_id": "a", "symbol": "PF_XBTUSD", "side": "buy",  "size": 0.1, "price": 60000,
                "fillTime": "2026-07-01T10:00:00.000Z", "fillType": "taker" },
              { "fill_id": "b", "symbol": "PF_XBTUSD", "side": "sell", "size": 0.1, "price": 61000,
                "fillTime": "2026-07-01T12:00:00.000Z", "fillType": "maker" }
            ] }
        """.trimIndent()

        val fills = connector.mapFills(parse(body))
        assertThat(fills).hasSize(2)

        val p = PositionReconstructor.reconstruct(fills, connector::normalizeSymbol).single()
            .let { it.copy(realizedPnl = PositionReconstructor.realizedFromPrices(it)) }

        // PF_XBTUSD: the contract-type prefix goes, and XBT becomes BTC.
        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USD"))
        assertThat(p.side).isEqualTo(PositionSide.LONG)
        assertThat(p.qty).isEqualByComparingTo("0.1")
        assertThat(p.realizedPnl).isEqualByComparingTo("100")
        // Kraken states its API fee values no longer reflect what was charged, so none is recorded and
        // net equals gross. This is asserted so the limitation cannot be lost silently in a refactor.
        assertThat(p.fees).isEqualByComparingTo("0")
        assertThat(net(p)).isEqualByComparingTo("100")
    }

    @Test
    fun `Kraken Futures skips inverse contracts`() {
        val connector = KrakenFuturesConnector(props, mapper)
        // PI_ is inverse: quote-denominated size and base-settled PnL, so it is not comparable.
        val body = """
            { "result": "success", "fills": [
              { "fill_id": "a", "symbol": "PI_XBTUSD", "side": "buy", "size": 100, "price": 60000,
                "fillTime": "2026-07-01T10:00:00.000Z", "fillType": "taker" },
              { "fill_id": "b", "symbol": "FI_XBTUSD_260626", "side": "buy", "size": 100, "price": 60000,
                "fillTime": "2026-07-01T10:00:00.000Z", "fillType": "taker" }
            ] }
        """.trimIndent()

        assertThat(connector.mapFills(parse(body))).isEmpty()
    }

    @Test
    fun `Kraken Futures normalizes an altcoin perpetual`() {
        val connector = KrakenFuturesConnector(props, mapper)
        assertThat(connector.normalizeSymbol("PF_ETHUSD")).isEqualTo(Symbol("ETH", "USD"))
        assertThat(connector.normalizeSymbol("PF_SOLUSD")).isEqualTo(Symbol("SOL", "USD"))
    }

    // ---------------------------------------------------------------------------------------------
    // BingX — hedge-mode fills name their position, which tells the reconstructor what may reduce it.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `BingX declares a hedge-mode fill's intent and leaves a one-way fill undeclared`() {
        val connector = BingxConnector(props, mapper)
        val body = """
            { "code": 0, "data": { "fill_orders": [
              { "filledTm": "2026-09-01T10:00:00Z", "symbol": "BTC-USDT", "side": "BUY",  "positionSide": "LONG",
                "price": "100", "amount": "100", "volume": "1", "commission": "-0.05" },
              { "filledTm": "2026-09-01T11:00:00Z", "symbol": "BTC-USDT", "side": "SELL", "positionSide": "LONG",
                "price": "110", "amount": "110", "volume": "1", "commission": "-0.05" },
              { "filledTm": "2026-09-01T12:00:00Z", "symbol": "ETH-USDT", "side": "BUY",  "positionSide": "SHORT",
                "price": "50", "amount": "100", "volume": "2", "commission": "-0.05" },
              { "filledTm": "2026-09-01T13:00:00Z", "symbol": "SOL-USDT", "side": "SELL", "positionSide": "BOTH",
                "price": "20", "amount": "40", "volume": "2", "commission": "-0.05" }
            ] } }
        """.trimIndent()

        val fills = connector.mapFills(parse(body))

        assertThat(fills.map { it.reduces }).containsExactly(false, true, true, null)
        // The closing SELL of an aged-out long is dropped, so a fresh round trip on the same side is what emits.
        val p = PositionReconstructor.reconstruct(fills.take(2), connector::normalizeSymbol).single()
        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USDT"))
        assertThat(p.side).isEqualTo(PositionSide.LONG)
        assertThat(p.exitPrice).isEqualByComparingTo("110")
    }

    // ---------------------------------------------------------------------------------------------
    // Hyperliquid — address-only; per-leg closedPnl, funding under a separate `delta` object.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `Hyperliquid folds fills and a funding payment into a USDC perpetual position`() {
        val connector = HyperliquidConnector(props, mapper)
        val fills = connector.mapUserFills(parse("""
            [
              { "coin": "BTC", "side": "B", "dir": "Open Long", "px": "60000", "sz": "0.1",
                "fee": "0.6", "builderFee": "0.05", "closedPnl": "0", "time": 1700000000000 },
              { "coin": "BTC", "side": "A", "dir": "Close Long", "px": "61000", "sz": "0.1",
                "fee": "0.61", "builderFee": "0", "closedPnl": "100", "time": 1700003600000 },
              { "coin": "BTC/USD", "side": "B", "px": "60000", "sz": "0.1", "fee": "1", "time": 1700007200000 }
            ]
        """.trimIndent()))

        assertThat(fills).hasSize(2)
        assertThat(fills.map { it.symbol }).containsOnly("BTC")

        val funding = connector.mapFundingPayments(parse("""
            [
              { "time": 1700001800000, "delta": { "coin": "BTC", "usdc": "-0.25" } }
            ]
        """.trimIndent()))

        assertThat(funding).hasSize(1)
        assertThat(funding.single().funding).isEqualByComparingTo("0.25")

        val p = PositionReconstructor.reconstruct(fills + funding, connector::normalizeSymbol).single()
        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USDC"))
        assertThat(p.side).isEqualTo(PositionSide.LONG)
        assertThat(p.qty).isEqualByComparingTo("0.1")
        assertThat(p.realizedPnl).isEqualByComparingTo("100")
        assertThat(p.fees).isEqualByComparingTo("1.26")
        assertThat(p.funding).isEqualByComparingTo("0.25")
        assertThat(net(p)).isEqualByComparingTo("98.49")
    }

    // ---------------------------------------------------------------------------------------------
    // Lighter — read-only token; the account index in the token decides which side of each trade.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `Lighter maps the user's side of each trade and drops rows that belong to others`() {
        val connector = LighterConnector(props, mapper)
        val markets = mapOf("1" to "BTC-PERP")
        val rows = connector.mapTrades(
            parse("""
                { "trades": [
                  { "market_id": "1", "timestamp": 1700000000, "price": "60000", "size": "0.1",
                    "bid_account_id": "7", "ask_account_id": "99", "is_maker_ask": "false",
                    "maker_fee": "0.1", "taker_fee": "0.2", "bid_account_pnl": "0", "ask_account_pnl": "0" },
                  { "market_id": "1", "timestamp": 1700003600, "price": "61000", "size": "0.1",
                    "bid_account_id": "99", "ask_account_id": "7", "is_maker_ask": "false",
                    "maker_fee": "0.1", "taker_fee": "0.2", "bid_account_pnl": "0", "ask_account_pnl": "100" },
                  { "market_id": "1", "timestamp": 1700007200, "price": "62000", "size": "0.1",
                    "bid_account_id": "8", "ask_account_id": "9", "is_maker_ask": "false",
                    "maker_fee": "0.1", "taker_fee": "0.2", "bid_account_pnl": "0", "ask_account_pnl": "0" }
                ] }
            """.trimIndent()),
            accountIndex = "7",
            markets = markets,
        )

        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.buy }).containsExactly(true, false)
        assertThat(rows[0].fee).isEqualByComparingTo("0.1")
        assertThat(rows[1].fee).isEqualByComparingTo("0.2")

        val p = PositionReconstructor.reconstruct(rows, connector::normalizeSymbol).single()
        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USDC"))
        assertThat(p.side).isEqualTo(PositionSide.LONG)
        assertThat(p.realizedPnl).isEqualByComparingTo("100")
        assertThat(p.fees).isEqualByComparingTo("0.3")
        assertThat(net(p)).isEqualByComparingTo("99.7")
    }
}
