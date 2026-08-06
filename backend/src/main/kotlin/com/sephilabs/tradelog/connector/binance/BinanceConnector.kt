// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.binance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.SortedMap

/**
 * Binance USDT-M futures. No closed-position endpoint, and `userTrades` requires a symbol — so
 * `/income` (which takes none) reveals which symbols traded per window, and carries the funding rows.
 */
@Component
class BinanceConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ReconstructingConnector(props.connectors.binance, mapper) {

    override val kind = SourceKind.BINANCE_FUTURES

    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw.substringBefore(GROUP_SEP))

    // Both endpoints cap a request at 7 days. Income retention (~3 months) bounds the useful depth.
    override val backfillDays = 90L
    override val pacingMs = 250L
    override val rateLimitCodes = setOf("-1003", "-1015", "429")
    override val authCodes = setOf("-2014", "-2015", "-1022", "-1099")
    override val permissionCodes = setOf("-2015", "-1002")

    /** `userTrades` reports `realizedPnl` per fill, so nothing has to be derived from prices. */
    override val derivePnlFromPrices = false

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
            headers = mapOf("X-MBX-APIKEY" to creds.apiKey),
        )
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        // A successful list response is a bare array; only errors come back as an object with a code.
        if (root.isArray) return
        val code = root.text(FIELD_CODE) ?: return
        failEnvelope(path, code, root.path("msg").asText(""))
    }

    override fun fetchFills(creds: ExchangeCredentials, start: Instant, end: Instant): List<RawFill> {
        val income = fetchIncome(creds, start, end)
        val symbols = income.mapNotNull { row ->
            row.text(FIELD_SYMBOL)?.takeIf { row.text(FIELD_INCOME_TYPE) in TRADING_INCOME }
        }.toSet()
        log.debug("Binance window {}..{}: {} income row(s), {} symbol(s) traded", start, end, income.size, symbols.size)

        val out = mutableListOf<RawFill>()
        out += fundingFills(income)
        for (symbol in symbols) {
            pace()
            out += fetchTrades(creds, symbol, start, end)
        }
        return out
    }

    private fun fetchIncome(creds: ExchangeCredentials, start: Instant, end: Instant): List<JsonNode> =
        pageThrough<Int>(maxPages = MAX_PAGES) { page ->
            val current = page ?: 1
            val rows = getJson(
                creds,
                PATH_INCOME,
                sortedMapOf(
                    "startTime" to start.toEpochMilli().toString(),
                    "endTime" to end.toEpochMilli().toString(),
                    "limit" to INCOME_LIMIT.toString(),
                    "page" to current.toString(),
                ),
            ).rows(ROW_ROOT)
            Page(rows, (current + 1).takeIf { rows.size >= INCOME_LIMIT })
        }

    private fun fetchTrades(creds: ExchangeCredentials, symbol: String, start: Instant, end: Instant): List<RawFill> {
        // `fromId` cannot be combined with a time range, so the time range is used and the last page is
        // recognised by being short — the 1,000-row cap is far above a week of trading in one symbol.
        val rows = pageThrough<Long>(maxPages = MAX_PAGES) { fromId ->
            val params = sortedMapOf(
                "symbol" to symbol,
                "limit" to TRADES_LIMIT.toString(),
            )
            if (fromId != null) {
                params["fromId"] = fromId.toString()
            } else {
                params["startTime"] = start.toEpochMilli().toString()
                params["endTime"] = end.toEpochMilli().toString()
            }
            val page = getJson(creds, PATH_TRADES, params).rows(ROW_ROOT)
            // Continue from the id after the last one seen; only a full page can have more behind it.
            val next = page.lastOrNull()?.long(FIELD_TRADE_ID)?.plus(1)?.takeIf { page.size >= TRADES_LIMIT }
            Page(page, next)
        }
        val skips = SkipTally()
        val mapped = mutableListOf<RawFill>()
        for (row in rows) skips.keep(row, mapTrade(row), mapped)
        skips.report(log, venue, "for $symbol in $start..$end", rows.size)
        return mapped
    }

    /** Test seam: map a raw `userTrades` body into fills, dropping unmappable rows. */
    internal fun mapTrades(node: JsonNode): List<RawFill> =
        node.rows(ROW_ROOT).mapNotNull { (mapTrade(it) as? Mapped.Ok)?.value }

    private fun mapTrade(n: JsonNode): Mapped<RawFill> {
        val symbol = n.text(FIELD_SYMBOL) ?: return Mapped.Skip("no symbol")
        val ts = n.instant(FIELD_TIME) ?: return Mapped.Skip("no timestamp")
        val price = n.dec(FIELD_PRICE) ?: return Mapped.Skip("no price")
        val qty = n.dec(FIELD_QTY) ?: return Mapped.Skip("no qty")
        val side = n.text(FIELD_SIDE)?.uppercase() ?: return Mapped.Skip("no side")
        // Hedge-mode accounts run a long and a short book on one symbol; keeping them in separate groups
        // stops the two netting each other out into one nonsensical lifecycle.
        val positionSide = n.text(FIELD_POSITION_SIDE)?.uppercase() ?: ONE_WAY_POSITION
        return Mapped.Ok(
            RawFill(
                symbol = "$symbol$GROUP_SEP$positionSide",
                ts = ts,
                buy = side == "BUY",
                price = price,
                qty = qty,
                fee = (n.dec(FIELD_COMMISSION) ?: BigDecimal.ZERO).abs(),
                realizedPnl = n.dec(FIELD_REALIZED_PNL) ?: BigDecimal.ZERO,
            ),
        )
    }

    /** Funding as zero-quantity fills; `FUNDING_FEE` is negative when charged, so it is negated. */
    private fun fundingFills(income: List<JsonNode>): List<RawFill> =
        income.mapNotNull { row ->
            if (row.text(FIELD_INCOME_TYPE) != INCOME_FUNDING) return@mapNotNull null
            val symbol = row.text(FIELD_SYMBOL) ?: return@mapNotNull null
            val ts = row.instant(FIELD_TIME) ?: return@mapNotNull null
            val amount = row.dec(FIELD_INCOME) ?: return@mapNotNull null
            RawFill(
                symbol = "$symbol$GROUP_SEP$ONE_WAY_POSITION",
                ts = ts,
                buy = true, // irrelevant at zero quantity; the reconstructor only takes the money
                price = BigDecimal.ZERO,
                qty = BigDecimal.ZERO,
                funding = amount.negate(),
            )
        }

    private companion object {
        const val PATH_TRADES = "/fapi/v1/userTrades"
        const val PATH_INCOME = "/fapi/v1/income"
        const val TRADES_LIMIT = 1000
        const val INCOME_LIMIT = 1000
        const val MAX_PAGES = 50

        /** Separates symbol from positionSide in the reconstruction grouping key. */
        const val GROUP_SEP = ' '

        /** What Binance calls the single book of a one-way (non-hedge) account. */
        const val ONE_WAY_POSITION = "BOTH"

        const val INCOME_FUNDING = "FUNDING_FEE"

        /** Income types that prove the symbol was actually traded in the window. */
        val TRADING_INCOME = setOf("REALIZED_PNL", "COMMISSION")

        /** Both endpoints return the list at the document root. */
        val ROW_ROOT = listOf("")

        val FIELD_CODE = listOf("code")
        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_SIDE = listOf("side")
        val FIELD_POSITION_SIDE = listOf("positionSide")
        val FIELD_PRICE = listOf("price")
        val FIELD_QTY = listOf("qty")
        val FIELD_COMMISSION = listOf("commission")
        val FIELD_REALIZED_PNL = listOf("realizedPnl")
        val FIELD_TIME = listOf("time")
        val FIELD_TRADE_ID = listOf("id")
        val FIELD_INCOME_TYPE = listOf("incomeType")
        val FIELD_INCOME = listOf("income")
    }
}
