// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.kucoin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.position.PositionSide
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.SortedMap

/**
 * KuCoin Futures closed positions, on its own host. Two quirks: `pnl` is NET of everything, so the
 * gross is backed out; and the endpoint reports no size at all, so quantity is derived from the money.
 */
@Component
class KucoinConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ClosedPositionConnector(props.connectors.kucoin, mapper, props) {

    override val kind = SourceKind.KUCOIN_FUTURES

    /** `XBTUSDTM` → BTC/USDT: drop the perpetual `M` suffix and KuCoin's `XBT` for bitcoin. */
    override fun normalizeSymbol(raw: String): Symbol {
        val base = raw.uppercase().removeSuffix(PERP_SUFFIX).replace(XBT, BTC)
        return Symbols.split(base)
    }

    override val pacingMs = 250L
    override val rateLimitCodes = setOf("429000", "1015", "429")
    override val authCodes = setOf("400001", "400002", "400003", "400004", "400005", "400006", "400007", "411100")
    override val permissionCodes = setOf("400008", "403000")

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        val queryString = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        val endpoint = if (queryString.isEmpty()) path else "$path?$queryString"
        val timestamp = Instant.now().toEpochMilli().toString()
        return Auth(
            headers = mapOf(
                "KC-API-KEY" to creds.apiKey,
                "KC-API-SIGN" to ExchangeSign.hmacSha256Base64(creds.apiSecret, timestamp + method.uppercase() + endpoint),
                "KC-API-TIMESTAMP" to timestamp,
                // v2 keys send the passphrase signed rather than in the clear.
                "KC-API-PASSPHRASE" to ExchangeSign.hmacSha256Base64(creds.apiSecret, requirePassphrase(creds)),
                "KC-API-KEY-VERSION" to "2",
                "Content-Type" to "application/json",
            ),
        )
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        val code = root.text(FIELD_CODE) ?: return
        if (code == SUCCESS_CODE) return
        failEnvelope(path, code, root.path("msg").asText(root.path("message").asText("")))
    }

    /** 7-day windows back to the retention floor, each paged by page number. */
    override fun fetchRows(creds: ExchangeCredentials, since: Instant?): List<JsonNode> {
        val now = Instant.now()
        val retention = now.minus(RETENTION_DAYS, ChronoUnit.DAYS)
        val floor = since?.coerceAtLeast(retention) ?: retention
        val out = mutableListOf<JsonNode>()
        var windowEnd = now
        var windows = 0
        while (windowEnd.isAfter(floor) && windows < MAX_WINDOWS) {
            val windowStart = maxOf(windowEnd.minus(WINDOW_DAYS, ChronoUnit.DAYS), floor)
            out += fetchWindow(creds, windowStart, windowEnd)
            windows++
            windowEnd = windowStart
            pace(windowEnd.isAfter(floor))
        }
        return out
    }

    private fun fetchWindow(creds: ExchangeCredentials, start: Instant, end: Instant): List<JsonNode> =
        pageThrough<Int>(maxPages = MAX_PAGES) { page ->
            val current = page ?: 1
            val root = getJson(
                creds,
                PATH_HISTORY,
                sortedMapOf(
                    "from" to start.toEpochMilli().toString(),
                    "to" to end.toEpochMilli().toString(),
                    "limit" to LIMIT.toString(),
                    "pageId" to current.toString(),
                ),
            )
            val rows = root.rows(ROW_PATHS)
            val totalPages = root.path("data").int(FIELD_TOTAL_PAGE) ?: Int.MAX_VALUE
            Page(rows, (current + 1).takeIf { rows.isNotEmpty() && current < totalPages })
        }

    /** Test seam: map a raw history-positions body. */
    internal fun mapRows(node: JsonNode): List<PositionRecord> =
        node.rows(ROW_PATHS).mapNotNull { (mapPosition(it) as? Mapped.Ok)?.value }

    override fun mapPosition(row: JsonNode): Mapped<PositionRecord> {
        val symbolRaw = row.text(FIELD_SYMBOL) ?: return Mapped.Skip("no symbol")
        val externalId = row.text(FIELD_CLOSE_ID) ?: return Mapped.Skip("no closeId")
        val openedAt = row.instant(FIELD_OPEN_TIME) ?: return Mapped.Skip("no open time")
        val closedAt = row.instant(FIELD_CLOSE_TIME) ?: openedAt
        val settle = row.text(FIELD_SETTLE)?.uppercase()
        if (settle != null && settle != SETTLE_USDT) return Mapped.Skip("not USDT-settled ($settle)")

        val net = row.dec(FIELD_PNL) ?: BigDecimal.ZERO
        val fees = (row.dec(FIELD_TRADE_FEE) ?: BigDecimal.ZERO).abs()
        // Documented as "funding fees paid/received"; negative when paid, so negated into a signed cost.
        val funding = (row.dec(FIELD_FUNDING_FEE) ?: BigDecimal.ZERO).negate()
        val gross = net.add(fees).add(funding)

        val side = parseSide(row) ?: return Mapped.Skip("no side")
        val entry = row.dec(FIELD_OPEN_PX) ?: BigDecimal.ZERO
        val exit = row.dec(FIELD_CLOSE_PX) ?: BigDecimal.ZERO
        return Mapped.Ok(
            PositionRecord(
                externalId = externalId,
                symbol = normalizeSymbol(symbolRaw),
                side = side,
                openedAt = openedAt,
                closedAt = closedAt,
                qty = derivedQty(side, entry, exit, gross),
                entryPrice = entry,
                exitPrice = exit,
                realizedPnl = gross,
                fees = fees,
                funding = funding,
                fills = emptyList(),
                raw = row.toString(),
            ),
        )
    }

    /** `side` is LONG/SHORT; older payloads only carry `type` as CLOSE_LONG / CLOSE_SHORT. */
    private fun parseSide(row: JsonNode): PositionSide? {
        val raw = row.text(FIELD_SIDE) ?: row.text(FIELD_TYPE) ?: return null
        return if (raw.uppercase().contains("SHORT")) PositionSide.SHORT else PositionSide.LONG
    }

    /** Quantity from the money: exact for a linear perp, undefined (zero) when entry == exit. */
    private fun derivedQty(side: PositionSide, entry: BigDecimal, exit: BigDecimal, gross: BigDecimal): BigDecimal {
        val move = if (side == PositionSide.LONG) exit.subtract(entry) else entry.subtract(exit)
        if (move.signum() == 0) return BigDecimal.ZERO
        return gross.divide(move, MC).abs()
    }

    private companion object {
        const val PATH_HISTORY = "/api/v1/history-positions"
        const val SUCCESS_CODE = "200000"
        const val LIMIT = 200
        const val MAX_PAGES = 50
        const val WINDOW_DAYS = 7L // maximum span for one request
        const val RETENTION_DAYS = 90L // "retained in the system for up to three months"
        const val MAX_WINDOWS = 16
        const val SETTLE_USDT = "USDT"
        const val PERP_SUFFIX = "M"
        const val XBT = "XBT"
        const val BTC = "BTC"

        val MC = MathContext(34, RoundingMode.HALF_EVEN)

        val ROW_PATHS = listOf("data.items", "data.dataList", "data")
        val FIELD_CODE = listOf("code")
        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_CLOSE_ID = listOf("closeId", "id")
        val FIELD_SETTLE = listOf("settleCurrency")
        val FIELD_SIDE = listOf("side")
        val FIELD_TYPE = listOf("type")
        val FIELD_OPEN_TIME = listOf("openTime")
        val FIELD_CLOSE_TIME = listOf("closeTime")
        val FIELD_OPEN_PX = listOf("openPrice")
        val FIELD_CLOSE_PX = listOf("closePrice")
        val FIELD_PNL = listOf("pnl")
        val FIELD_TRADE_FEE = listOf("tradeFee")
        val FIELD_FUNDING_FEE = listOf("fundingFee")
        val FIELD_TOTAL_PAGE = listOf("totalPage")
    }
}
