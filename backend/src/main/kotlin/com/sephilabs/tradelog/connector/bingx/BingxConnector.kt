// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.bingx

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.util.SortedMap

/** BingX USDT-M perpetuals, reconstructed from fills. ~30 days of history is all its API serves. */
@Component
class BingxConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ReconstructingConnector(props.connectors.bingx, mapper) {

    override val kind = SourceKind.BINGX

    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw.substringBefore(GROUP_SEP))

    // BingX serves ~30 days of fills; 90 is a backstop well past that, and the empty-window streak
    // (~2 months of silence) normally ends the walk first.
    override val backfillDays = 90L
    override val pacingMs = 250L
    override val rateLimitCodes = setOf("100410") // "rate limited"
    override val authCodes = setOf("100413", "100001") // signature / api-key errors
    override val permissionCodes = setOf("100419", "100403") // permission denied

    /** BingX fills carry no PnL field, so it is derived from the volume-weighted leg prices. */
    override val derivePnlFromPrices = true

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        query["timestamp"] = Instant.now().toEpochMilli().toString()
        val canonical = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        return Auth(
            query = mapOf("signature" to ExchangeSign.hmacSha256Hex(creds.apiSecret, canonical)),
            headers = mapOf("X-BX-APIKEY" to creds.apiKey),
        )
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        val code = root.path("code").asInt(0)
        if (code == 0) return
        failEnvelope(path, code.toString(), root.path("msg").asText(""))
    }

    override fun fetchFills(creds: ExchangeCredentials, start: Instant, end: Instant): List<RawFill> {
        val node = getJson(
            creds,
            PATH_FILLS,
            mapOf(
                "startTs" to start.toEpochMilli().toString(),
                "endTs" to end.toEpochMilli().toString(),
            ),
        )
        val fills = node.rows(ROW_PATHS)
        val skips = SkipTally()
        val mapped = mutableListOf<RawFill>()
        for (row in fills) skips.keep(row, mapFill(row), mapped)
        skips.report(log, venue, "in window $start..$end", fills.size)
        return mapped
    }

    /** Test seam: map a raw `/trade/allFillOrders` body into fills, dropping unmappable rows. */
    internal fun mapFills(node: JsonNode): List<RawFill> =
        node.rows(ROW_PATHS).mapNotNull { (mapFill(it) as? Mapped.Ok)?.value }

    private fun mapFill(n: JsonNode): Mapped<RawFill> {
        val symbol = n.text(FIELD_SYMBOL) ?: return Mapped.Skip("no symbol")
        val ts = n.instant(FIELD_TIME) ?: return Mapped.Skip("no timestamp")
        val side = n.text(FIELD_SIDE)?.uppercase() ?: return Mapped.Skip("no side")
        val price = n.dec(FIELD_PRICE) ?: return Mapped.Skip("no price")
        // BingX fills carry no base-quantity field — `volume` is scaled (×1000 vs the exchange UI).
        // Derive the true base qty from the USDT notional `amount` ÷ price, which matches the UI.
        val notional = n.dec(FIELD_AMOUNT)
        val qty = if (notional != null && price.signum() > 0) notional.divide(price, MC)
        else n.dec(FIELD_QTY) ?: BigDecimal.ZERO
        val positionSide = n.text(FIELD_POSITION_SIDE)?.uppercase()
        return Mapped.Ok(
            RawFill(
                symbol = if (positionSide != null) "$symbol$GROUP_SEP$positionSide" else symbol,
                ts = ts,
                buy = side == "BUY",
                price = price,
                qty = qty,
                fee = (n.dec(FIELD_FEE) ?: BigDecimal.ZERO).abs(),
                realizedPnl = BigDecimal.ZERO, // computed from leg prices post-reconstruction
            ),
        )
    }

    private companion object {
        const val PATH_FILLS = "/openApi/swap/v2/trade/allFillOrders"

        /** Separates symbol from positionSide in the reconstruction grouping key. */
        const val GROUP_SEP = ' '

        val MC = MathContext(34, RoundingMode.HALF_EVEN) // for notional ÷ price → base qty

        val ROW_PATHS = listOf("data", "data.fill_orders", "data.fillOrders", "data.orders")
        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_AMOUNT = listOf("amount") // fill notional in quote (USDT)
        val FIELD_SIDE = listOf("side")
        val FIELD_POSITION_SIDE = listOf("positionSide")
        val FIELD_PRICE = listOf("price", "avgPrice", "fillPrice")
        val FIELD_QTY = listOf("qty", "volume", "amount", "executedQty")
        val FIELD_FEE = listOf("commission", "fee")
        val FIELD_TIME = listOf("filledTm", "filledTime", "time", "tradeTime", "updateTime", "ctime")
    }
}
