// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.bybit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.SortedMap

/** Bybit linear (USDT-settled) perpetuals, built on fills rather than on Bybit's closed-PnL endpoint. */
@Component
class BybitConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ReconstructingConnector(props.connectors.bybit, mapper) {

    override val kind = SourceKind.BYBIT

    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw)

    // 7 days is Bybit's maximum span per request; retention is ~2 years.
    override val backfillDays = 730L
    override val pacingMs = 250L
    override val rateLimitCodes = setOf("10006", "10018", "429")
    override val authCodes = setOf("10003", "10004", "10005", "33004", "10002")
    override val permissionCodes = setOf("10005", "10016")

    /** Bybit's execution rows carry no per-fill PnL, so it is derived from the leg prices. */
    override val derivePnlFromPrices = true

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        val timestamp = Instant.now().toEpochMilli().toString()
        val queryString = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        val signature = ExchangeSign.hmacSha256Hex(
            creds.apiSecret,
            timestamp + creds.apiKey + RECV_WINDOW + queryString,
        )
        return Auth(
            headers = mapOf(
                "X-BAPI-API-KEY" to creds.apiKey,
                "X-BAPI-TIMESTAMP" to timestamp,
                "X-BAPI-RECV-WINDOW" to RECV_WINDOW,
                "X-BAPI-SIGN" to signature,
            ),
        )
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        val code = root.path("retCode").asText("0")
        if (code == "0" || code.isBlank()) return
        failEnvelope(path, code, root.path("retMsg").asText(""))
    }

    override fun fetchFills(creds: ExchangeCredentials, start: Instant, end: Instant): List<RawFill> {
        val rows = pageThrough<String>(maxPages = MAX_PAGES) { cursor ->
            val params = sortedMapOf(
                "category" to CATEGORY,
                "startTime" to start.toEpochMilli().toString(),
                "endTime" to end.toEpochMilli().toString(),
                "limit" to LIMIT.toString(),
            )
            cursor?.let { params["cursor"] = it }
            val root = getJson(creds, PATH_EXECUTIONS, params)
            val page = root.rows(ROW_PATHS)
            // Bybit hands back an empty cursor string rather than omitting it when the walk is over.
            Page(page, root.path("result").text(FIELD_CURSOR))
        }
        val skips = SkipTally()
        val mapped = mutableListOf<RawFill>()
        for (row in rows) skips.keep(row, mapExecution(row), mapped)
        skips.report(log, venue, "in window $start..$end", rows.size)
        return mapped
    }

    /** Test seam: map a raw `/v5/execution/list` body into fills, dropping unmappable rows. */
    internal fun mapExecutions(node: JsonNode): List<RawFill> =
        node.rows(ROW_PATHS).mapNotNull { (mapExecution(it) as? Mapped.Ok)?.value }

    private fun mapExecution(n: JsonNode): Mapped<RawFill> {
        val symbol = n.text(FIELD_SYMBOL) ?: return Mapped.Skip("no symbol")
        val ts = n.instant(FIELD_TIME) ?: return Mapped.Skip("no timestamp")
        val execType = n.text(FIELD_EXEC_TYPE) ?: EXEC_TRADE
        val fee = (n.dec(FIELD_FEE) ?: BigDecimal.ZERO)

        // Funding settles through the same stream with no quantity; Bybit charges it positive,
        // which is already the cost convention, so it passes through as a money-only fill.
        if (execType.equals(EXEC_FUNDING, ignoreCase = true)) {
            return Mapped.Ok(
                RawFill(
                    symbol = symbol,
                    ts = ts,
                    buy = true, // irrelevant at zero quantity
                    price = BigDecimal.ZERO,
                    qty = BigDecimal.ZERO,
                    funding = fee,
                ),
            )
        }
        if (execType !in TRADE_TYPES) return Mapped.Skip("non-trade execType=$execType")

        val price = n.dec(FIELD_PRICE) ?: return Mapped.Skip("no price")
        val qty = n.dec(FIELD_QTY) ?: return Mapped.Skip("no qty")
        val side = n.text(FIELD_SIDE)?.uppercase() ?: return Mapped.Skip("no side")
        return Mapped.Ok(
            RawFill(
                symbol = symbol,
                ts = ts,
                buy = side == "BUY",
                price = price,
                qty = qty,
                fee = fee.abs(),
                realizedPnl = BigDecimal.ZERO, // derived from the leg prices after reconstruction
            ),
        )
    }

    private companion object {
        const val PATH_EXECUTIONS = "/v5/execution/list"
        const val CATEGORY = "linear"
        const val RECV_WINDOW = "5000"
        const val LIMIT = 100
        const val MAX_PAGES = 100

        const val EXEC_TRADE = "Trade"
        const val EXEC_FUNDING = "Funding"

        /** Execution types that move exposure. Everything else is a settlement or bookkeeping row. */
        val TRADE_TYPES = setOf("Trade", "AdlTrade", "BustTrade")

        val ROW_PATHS = listOf("result.list")
        val FIELD_CURSOR = listOf("nextPageCursor")
        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_SIDE = listOf("side")
        val FIELD_PRICE = listOf("execPrice")
        val FIELD_QTY = listOf("execQty")
        val FIELD_FEE = listOf("execFee")
        val FIELD_TIME = listOf("execTime")
        val FIELD_EXEC_TYPE = listOf("execType")
    }
}
