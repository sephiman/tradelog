// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.bitget

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.position.PositionSide
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.SortedMap

/**
 * Bitget USDT-M closed positions, shared by both API generations: v2 and v3 differ only in the path and
 * two parameter names, and the field lookups below accept either generation's spelling.
 */
abstract class BitgetPositionConnector(
    endpoint: AppProperties.ExchangeEndpoint,
    mapper: ObjectMapper,
    props: AppProperties,
) : ClosedPositionConnector(endpoint, mapper, props) {

    /** The history endpoint of this API generation. */
    protected abstract val historyPath: String

    /** What this generation calls the product-type parameter: `category` on v3, `productType` on v2. */
    protected abstract val productParam: String

    /** What this generation calls the pagination cursor: `cursor` on v3, `idLessThan` on v2. */
    protected abstract val cursorParam: String

    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw)

    // 20 requests/second per UID — far above what a three-window walk needs.
    override val pacingMs = 200L
    override val rateLimitCodes = setOf("429", "40018", "45110")
    override val authCodes = setOf("40001", "40006", "40009", "40012", "40037", "40409")
    override val permissionCodes = setOf("40014", "40024", "40029")

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        // Bitget signs the query string WITH its leading '?', built from the same sorted map the base
        // class sends; the body is empty for a GET.
        val queryString = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        val timestamp = Instant.now().toEpochMilli().toString()
        val payload = timestamp + method.uppercase() + path + if (queryString.isEmpty()) "" else "?$queryString"
        return Auth(
            headers = mapOf(
                "ACCESS-KEY" to creds.apiKey,
                "ACCESS-SIGN" to ExchangeSign.hmacSha256Base64(creds.apiSecret, payload),
                "ACCESS-TIMESTAMP" to timestamp,
                "ACCESS-PASSPHRASE" to requirePassphrase(creds),
                "locale" to "en-US",
                "Content-Type" to "application/json",
            ),
        )
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        val code = root.path("code").asText(SUCCESS_CODE)
        if (code == SUCCESS_CODE || code.isBlank()) return
        failEnvelope(path, code, root.path("msg").asText(""))
    }

    /** 30-day windows (Bitget's max span) back to the 90-day retention floor, each paged by cursor. */
    override fun fetchRows(creds: ExchangeCredentials, since: Instant?): List<JsonNode> {
        val now = Instant.now()
        // Never ask for more than Bitget retains, however far back the watermark or sync_from reaches.
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
        pageThrough<String>(maxPages = MAX_PAGES) { cursor ->
            val params = sortedMapOf(
                productParam to PRODUCT,
                "limit" to LIMIT.toString(),
                "startTime" to start.toEpochMilli().toString(),
                "endTime" to end.toEpochMilli().toString(),
            )
            cursor?.let { params[cursorParam] = it }
            val root = getJson(creds, historyPath, params)
            val rows = root.rows(ROW_PATHS)
            // The continuation arrives on the envelope; a short page ends the walk either way.
            val next = root.path("data").text(FIELD_CURSOR) ?: rows.lastOrNull()?.text(FIELD_CURSOR)
            Page(rows, next?.takeIf { rows.size >= LIMIT })
        }

    /** Test seam: map a raw history-position body of either generation. */
    internal fun mapRows(node: JsonNode): List<PositionRecord> =
        node.rows(ROW_PATHS).mapNotNull { (mapPosition(it) as? Mapped.Ok)?.value }

    override fun mapPosition(row: JsonNode): Mapped<PositionRecord> {
        val symbolRaw = row.text(FIELD_SYMBOL) ?: return Mapped.Skip("no symbol")
        val externalId = row.text(FIELD_POSITION_ID) ?: return Mapped.Skip("no positionId")
        val openedAt = row.instant(FIELD_OPEN_TIME) ?: return Mapped.Skip("no open time")
        val closedAt = row.instant(FIELD_CLOSE_TIME) ?: openedAt

        // The PnL field is gross (fees and funding excluded); both fee components and funding are
        // negative when charged, so they are negated into costs. Net derives back to `netProfit`.
        val gross = row.dec(FIELD_PNL) ?: BigDecimal.ZERO
        val openFee = row.dec(FIELD_OPEN_FEE) ?: BigDecimal.ZERO
        val closeFee = row.dec(FIELD_CLOSE_FEE) ?: BigDecimal.ZERO
        val funding = row.dec(FIELD_FUNDING) ?: BigDecimal.ZERO
        return Mapped.Ok(
            PositionRecord(
                externalId = externalId,
                symbol = normalizeSymbol(symbolRaw),
                side = if (row.text(FIELD_SIDE)?.lowercase()?.contains("short") == true) PositionSide.SHORT else PositionSide.LONG,
                openedAt = openedAt,
                closedAt = closedAt,
                qty = row.dec(FIELD_CLOSE_POS) ?: row.dec(FIELD_OPEN_POS) ?: BigDecimal.ZERO,
                entryPrice = row.dec(FIELD_OPEN_PX) ?: BigDecimal.ZERO,
                exitPrice = row.dec(FIELD_CLOSE_PX) ?: BigDecimal.ZERO,
                realizedPnl = gross,
                fees = openFee.abs().add(closeFee.abs()),
                funding = funding.negate(),
                fills = emptyList(),
                raw = row.toString(),
            ),
        )
    }

    protected companion object {
        const val PRODUCT = "USDT-FUTURES"
        const val SUCCESS_CODE = "00000"
        const val LIMIT = 100
        const val MAX_PAGES = 60
        const val WINDOW_DAYS = 30L // Bitget's maximum span for one request
        const val RETENTION_DAYS = 90L // and how far back it keeps anything at all
        const val MAX_WINDOWS = 4

        val ROW_PATHS = listOf("data.list", "data")
        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_POSITION_ID = listOf("positionId")
        val FIELD_SIDE = listOf("posSide", "holdSide")
        val FIELD_OPEN_TIME = listOf("createdTime", "ctime")
        val FIELD_CLOSE_TIME = listOf("updatedTime", "utime")
        val FIELD_OPEN_PX = listOf("openPriceAvg", "openAvgPrice")
        val FIELD_CLOSE_PX = listOf("closePriceAvg", "closeAvgPrice")
        val FIELD_OPEN_POS = listOf("openTotalPos")
        val FIELD_CLOSE_POS = listOf("closeTotalPos")
        val FIELD_PNL = listOf("cumRealisedPnl", "pnl")
        val FIELD_OPEN_FEE = listOf("openFeeTotal", "openFee")
        val FIELD_CLOSE_FEE = listOf("closeFeeTotal", "closeFee")
        val FIELD_FUNDING = listOf("totalFunding")
        val FIELD_CURSOR = listOf("cursor", "endId")
    }
}
