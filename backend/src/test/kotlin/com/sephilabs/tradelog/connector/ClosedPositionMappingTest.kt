// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.bitget.BitgetClassicConnector
import com.sephilabs.tradelog.connector.bitget.BitgetConnector
import com.sephilabs.tradelog.connector.gateio.GateioConnector
import com.sephilabs.tradelog.connector.kucoin.KucoinConnector
import com.sephilabs.tradelog.connector.mexc.MexcConnector
import com.sephilabs.tradelog.connector.okx.OkxConnector
import com.sephilabs.tradelog.position.PositionSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** The money mapping of every consolidated closed-position connector. */
class ClosedPositionMappingTest {

    private val props = AppProperties()
    private val mapper = ObjectMapper()
    private fun parse(json: String) = ObjectMapper().readTree(json)

    /** What the sync engine will store: net = gross − fees − funding. Mirrors PositionUpsertService. */
    private fun net(r: PositionRecord): BigDecimal = r.realizedPnl.subtract(r.fees).subtract(r.funding)

    // ---------------------------------------------------------------------------------------------
    // OKX — reports gross `pnl` plus negative fee / fundingFee / liqPenalty, and its own realizedPnl.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `OKX maps a closed long and derives OKX's own realizedPnl`() {
        val body = """
            { "code": "0", "data": [ {
              "instId": "BTC-USDT-SWAP", "posId": "684", "direction": "long",
              "openAvgPx": "60000", "closeAvgPx": "61000",
              "closeTotalPos": "10", "openMaxPos": "10",
              "pnl": "100", "fee": "-2", "fundingFee": "-1", "liqPenalty": "0", "realizedPnl": "97",
              "cTime": "1700000000000", "uTime": "1700003600000"
            } ] }
        """.trimIndent()
        // BTC-USDT-SWAP trades 0.01 BTC per contract.
        val sizes = ContractSizes.of(mapOf("BTC-USDT-SWAP" to BigDecimal("0.01")))

        val p = OkxConnector(props, mapper).mapRows(parse(body), sizes).single()

        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USDT"))
        assertThat(p.side).isEqualTo(PositionSide.LONG)
        assertThat(p.externalId).isEqualTo("684")
        assertThat(p.openedAt).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L))
        assertThat(p.closedAt).isEqualTo(Instant.ofEpochMilli(1_700_003_600_000L))
        // 10 contracts * 0.01 BTC
        assertThat(p.qty).isEqualByComparingTo("0.1")
        assertThat(p.realizedPnl).isEqualByComparingTo("100")
        assertThat(p.fees).isEqualByComparingTo("2")
        assertThat(p.funding).isEqualByComparingTo("1")
        assertThat(net(p)).isEqualByComparingTo("97")
    }

    @Test
    fun `OKX folds a liquidation penalty into fees and reads a short`() {
        val body = """
            { "code": "0", "data": [ {
              "instId": "ETH-USDT-SWAP", "posId": "9", "direction": "short",
              "openAvgPx": "3000", "closeAvgPx": "3100", "closeTotalPos": "1",
              "pnl": "-100", "fee": "-1", "fundingFee": "0.5", "liqPenalty": "-3", "realizedPnl": "-103.5",
              "cTime": "1700000000000", "uTime": "1700001000000"
            } ] }
        """.trimIndent()

        val p = OkxConnector(props, mapper).mapRows(parse(body), ContractSizes.NONE).single()

        assertThat(p.side).isEqualTo(PositionSide.SHORT)
        assertThat(p.fees).isEqualByComparingTo("4") // |fee| + |liqPenalty|
        // Funding RECEIVED is a negative cost, which increases the net.
        assertThat(p.funding).isEqualByComparingTo("-0.5")
        assertThat(net(p)).isEqualByComparingTo("-103.5")
    }

    @Test
    fun `OKX skips an inverse swap rather than importing a quote-denominated size`() {
        val body = """
            { "code": "0", "data": [ {
              "instId": "BTC-USD-SWAP", "posId": "1", "direction": "long",
              "openAvgPx": "60000", "closeAvgPx": "61000", "closeTotalPos": "1",
              "pnl": "1", "cTime": "1700000000000", "uTime": "1700001000000"
            } ] }
        """.trimIndent()

        assertThat(OkxConnector(props, mapper).mapRows(parse(body), ContractSizes.NONE)).isEmpty()
    }

    // ---------------------------------------------------------------------------------------------
    // Bitget UTA v3 — gross `cumRealisedPnl`, two negative fee components, negative funding.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `Bitget maps a closed short and derives Bitget's own netProfit`() {
        val body = """
            { "code": "00000", "data": { "list": [ {
              "positionId": "1210", "symbol": "BTCUSDT", "posSide": "short", "marginCoin": "USDT",
              "openPriceAvg": "61000", "closePriceAvg": "60000",
              "openTotalPos": "0.5", "closeTotalPos": "0.5",
              "cumRealisedPnl": "100", "netProfit": "96",
              "openFeeTotal": "-1", "closeFeeTotal": "-1", "totalFunding": "-2",
              "createdTime": "1700000000000", "updatedTime": "1700007200000"
            } ] } }
        """.trimIndent()

        val p = BitgetConnector(props, mapper).mapRows(parse(body)).single()

        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USDT"))
        assertThat(p.side).isEqualTo(PositionSide.SHORT)
        // Bitget quotes size in the base coin, so no contract scaling is involved.
        assertThat(p.qty).isEqualByComparingTo("0.5")
        assertThat(p.realizedPnl).isEqualByComparingTo("100")
        assertThat(p.fees).isEqualByComparingTo("2")
        assertThat(p.funding).isEqualByComparingTo("2")
        assertThat(net(p)).isEqualByComparingTo("96")
    }

    @Test
    fun `Bitget classic v2 maps the same figures under its older field names`() {
        // The classic API renames most of these (holdSide, openAvgPrice, pnl, openFee, ctime) and wraps
        // its cursor as endId. Both generations go through one mapping, so this pins that they agree.
        val body = """
            { "code": "00000", "data": { "endId": "1209", "list": [ {
              "positionId": "1210", "symbol": "BTCUSDT", "holdSide": "short", "marginCoin": "USDT",
              "openAvgPrice": "61000", "closeAvgPrice": "60000",
              "openTotalPos": "0.5", "closeTotalPos": "0.5",
              "pnl": "100", "netProfit": "96",
              "openFee": "-1", "closeFee": "-1", "totalFunding": "-2",
              "ctime": "1700000000000", "utime": "1700007200000"
            } ] } }
        """.trimIndent()

        val p = BitgetClassicConnector(props, mapper).mapRows(parse(body)).single()

        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USDT"))
        assertThat(p.side).isEqualTo(PositionSide.SHORT)
        assertThat(p.qty).isEqualByComparingTo("0.5")
        assertThat(p.realizedPnl).isEqualByComparingTo("100")
        assertThat(p.fees).isEqualByComparingTo("2")
        assertThat(p.funding).isEqualByComparingTo("2")
        assertThat(net(p)).isEqualByComparingTo("96")
    }

    @Test
    fun `both Bitget generations produce identical positions from their own payloads`() {
        val uta = """
            { "code": "00000", "data": { "list": [ {
              "positionId": "77", "symbol": "ETHUSDT", "posSide": "long",
              "openPriceAvg": "3000", "closePriceAvg": "3100", "closeTotalPos": "2",
              "cumRealisedPnl": "200", "openFeeTotal": "-1.5", "closeFeeTotal": "-1.5",
              "totalFunding": "-1", "createdTime": "1700000000000", "updatedTime": "1700007200000"
            } ] } }
        """.trimIndent()
        val classic = """
            { "code": "00000", "data": { "list": [ {
              "positionId": "77", "symbol": "ETHUSDT", "holdSide": "long",
              "openAvgPrice": "3000", "closeAvgPrice": "3100", "closeTotalPos": "2",
              "pnl": "200", "openFee": "-1.5", "closeFee": "-1.5",
              "totalFunding": "-1", "ctime": "1700000000000", "utime": "1700007200000"
            } ] } }
        """.trimIndent()

        val fromUta = BitgetConnector(props, mapper).mapRows(parse(uta)).single()
        val fromClassic = BitgetClassicConnector(props, mapper).mapRows(parse(classic)).single()

        // `raw` differs (it is the verbatim payload); everything the journal stores must not.
        assertThat(fromClassic.copy(raw = null)).isEqualTo(fromUta.copy(raw = null))
    }

    // ---------------------------------------------------------------------------------------------
    // Gate.io — the PnL arrives pre-split, and the prices are named by side rather than by role.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `Gate maps a closed long, splitting side-relative prices into entry and exit`() {
        val body = """
            [ {
              "time": 1700003600.5, "first_open_time": 1700000000, "contract": "BTC_USDT", "side": "long",
              "long_price": "60000", "short_price": "61000",
              "pnl": "98", "pnl_pnl": "100", "pnl_fee": "-1.5", "pnl_fund": "-0.5",
              "accum_size": "1000", "max_size": "1000", "text": "web"
            } ]
        """.trimIndent()
        // BTC_USDT is 0.0001 BTC per contract.
        val sizes = ContractSizes.of(mapOf("BTC_USDT" to BigDecimal("0.0001")))

        val p = GateioConnector(props, mapper).mapRows(parse(body), sizes).single()

        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USDT"))
        assertThat(p.side).isEqualTo(PositionSide.LONG)
        assertThat(p.entryPrice).isEqualByComparingTo("60000")
        assertThat(p.exitPrice).isEqualByComparingTo("61000")
        assertThat(p.qty).isEqualByComparingTo("0.1") // 1000 contracts * 0.0001
        // The fractional epoch second survives to the millisecond.
        assertThat(p.closedAt).isEqualTo(Instant.ofEpochMilli(1_700_003_600_500L))
        assertThat(p.openedAt).isEqualTo(Instant.ofEpochSecond(1_700_000_000L))
        assertThat(net(p)).isEqualByComparingTo("98")
    }

    @Test
    fun `Gate reverses the price roles for a short and keys the position stably`() {
        val body = """
            [ {
              "time": 1700003600, "first_open_time": 1700000000, "contract": "ETH_USDT", "side": "short",
              "long_price": "3100", "short_price": "3000",
              "pnl": "48", "pnl_pnl": "50", "pnl_fee": "-1", "pnl_fund": "-1", "accum_size": "1"
            } ]
        """.trimIndent()
        val connector = GateioConnector(props, mapper)

        val p = connector.mapRows(parse(body), ContractSizes.NONE).single()

        assertThat(p.side).isEqualTo(PositionSide.SHORT)
        // A short OPENS at short_price and CLOSES at long_price — the reverse of a long.
        assertThat(p.entryPrice).isEqualByComparingTo("3000")
        assertThat(p.exitPrice).isEqualByComparingTo("3100")
        assertThat(net(p)).isEqualByComparingTo("48")
        // Gate gives no position id, so the derived one must be identical on a re-sync or the upsert
        // would insert a duplicate of a position already recorded.
        assertThat(connector.mapRows(parse(body), ContractSizes.NONE).single().externalId)
            .isEqualTo(p.externalId)
        assertThat(p.externalId).contains("ETH_USDT")
    }

    // ---------------------------------------------------------------------------------------------
    // MEXC — `closeProfitLoss` is gross, `realised` is net, `holdFee` is positive when RECEIVED.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `MEXC maps a closed long and derives MEXC's own realised figure`() {
        val body = """
            { "success": true, "code": 0, "data": { "totalPage": 1, "resultList": [ {
              "positionId": 7788, "symbol": "BTC_USDT", "positionType": 1, "openType": 1, "state": 3,
              "holdVol": 100, "closeVol": 100,
              "openAvgPrice": 60000, "closeAvgPrice": 61000, "holdAvgPrice": 60000,
              "closeProfitLoss": 100, "realised": 95, "fee": 3, "totalFee": 3, "holdFee": -2,
              "leverage": 10, "createTime": 1700000000000, "updateTime": 1700003600000
            } ] } }
        """.trimIndent()
        // BTC_USDT is 0.0001 BTC per contract.
        val sizes = ContractSizes.of(mapOf("BTC_USDT" to BigDecimal("0.0001")))

        val p = MexcConnector(props, mapper).mapRows(parse(body), sizes).single()

        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USDT"))
        assertThat(p.side).isEqualTo(PositionSide.LONG)
        assertThat(p.qty).isEqualByComparingTo("0.01") // 100 contracts * 0.0001
        assertThat(p.realizedPnl).isEqualByComparingTo("100")
        assertThat(p.fees).isEqualByComparingTo("3")
        // holdFee is documented positive = received, so -2 was PAID and becomes a +2 cost.
        assertThat(p.funding).isEqualByComparingTo("2")
        assertThat(net(p)).isEqualByComparingTo("95")
    }

    @Test
    fun `MEXC skips a position that is still open`() {
        val body = """
            { "success": true, "data": { "resultList": [ {
              "positionId": 1, "symbol": "BTC_USDT", "positionType": 2, "state": 1,
              "closeVol": 0, "openAvgPrice": 60000, "closeAvgPrice": 0, "closeProfitLoss": 0,
              "createTime": 1700000000000, "updateTime": 1700000000000
            } ] } }
        """.trimIndent()

        assertThat(MexcConnector(props, mapper).mapRows(parse(body), ContractSizes.NONE)).isEmpty()
    }

    // ---------------------------------------------------------------------------------------------
    // KuCoin Futures — `pnl` is NET, so gross is backed out; quantity has to be derived.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `KuCoin backs the gross out of a net pnl and recovers it exactly`() {
        val body = """
            { "code": "200000", "data": { "totalPage": 1, "items": [ {
              "closeId": "300", "symbol": "XBTUSDTM", "settleCurrency": "USDT", "side": "LONG",
              "type": "CLOSE_LONG", "leverage": "10",
              "openPrice": "60000", "closePrice": "61000",
              "pnl": "95", "tradeFee": "3", "fundingFee": "-2",
              "openTime": 1700000000000, "closeTime": 1700003600000
            } ] } }
        """.trimIndent()

        val p = KucoinConnector(props, mapper).mapRows(parse(body)).single()

        // XBTUSDTM: the trailing M and Kraken-style XBT are both undone.
        assertThat(p.symbol).isEqualTo(Symbol("BTC", "USDT"))
        assertThat(p.side).isEqualTo(PositionSide.LONG)
        assertThat(p.fees).isEqualByComparingTo("3")
        assertThat(p.funding).isEqualByComparingTo("2")
        assertThat(p.realizedPnl).isEqualByComparingTo("100") // 95 net + 3 fees + 2 funding
        // The whole point of backing it out: net must land back on KuCoin's own figure.
        assertThat(net(p)).isEqualByComparingTo("95")
        // Quantity is derived from the money: gross 100 over a 1,000 move = 0.1 BTC.
        assertThat(p.qty).isEqualByComparingTo("0.1")
    }

    @Test
    fun `KuCoin derives a short's quantity from the reversed price move`() {
        val body = """
            { "code": "200000", "data": { "items": [ {
              "closeId": "301", "symbol": "ETHUSDTM", "settleCurrency": "USDT", "side": "SHORT",
              "openPrice": "3100", "closePrice": "3000",
              "pnl": "48", "tradeFee": "1", "fundingFee": "-1",
              "openTime": 1700000000000, "closeTime": 1700003600000
            } ] } }
        """.trimIndent()

        val p = KucoinConnector(props, mapper).mapRows(parse(body)).single()

        assertThat(p.symbol).isEqualTo(Symbol("ETH", "USDT"))
        assertThat(p.side).isEqualTo(PositionSide.SHORT)
        assertThat(p.realizedPnl).isEqualByComparingTo("50")
        assertThat(net(p)).isEqualByComparingTo("48")
        // A short profits as price falls: gross 50 over a 100 move = 0.5 ETH.
        assertThat(p.qty).isEqualByComparingTo("0.5")
    }

    @Test
    fun `KuCoin keeps the money exact when the quantity cannot be derived`() {
        // Opened and closed at the same price: qty is genuinely unknowable from this endpoint, and a
        // fabricated size would be worse than none. The PnL the analytics run on stays correct.
        val body = """
            { "code": "200000", "data": { "items": [ {
              "closeId": "302", "symbol": "XBTUSDTM", "settleCurrency": "USDT", "side": "LONG",
              "openPrice": "60000", "closePrice": "60000",
              "pnl": "-4", "tradeFee": "4", "fundingFee": "0",
              "openTime": 1700000000000, "closeTime": 1700000600000
            } ] } }
        """.trimIndent()

        val p = KucoinConnector(props, mapper).mapRows(parse(body)).single()

        assertThat(p.qty).isEqualByComparingTo("0")
        assertThat(p.fees).isEqualByComparingTo("4")
        assertThat(net(p)).isEqualByComparingTo("-4")
    }

    @Test
    fun `KuCoin skips a contract that does not settle in USDT`() {
        // An inverse contract's PnL is in the base coin, so it does not belong in a USDT column.
        val body = """
            { "code": "200000", "data": { "items": [ {
              "closeId": "303", "symbol": "XBTUSDM", "settleCurrency": "XBT", "side": "LONG",
              "openPrice": "60000", "closePrice": "61000", "pnl": "0.001", "tradeFee": "0",
              "openTime": 1700000000000, "closeTime": 1700003600000
            } ] } }
        """.trimIndent()

        assertThat(KucoinConnector(props, mapper).mapRows(parse(body))).isEmpty()
    }
}
