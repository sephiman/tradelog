// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.lighter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Lighter perpetual futures. Authenticates with a read-only token (`ro:account_index:...`); the
 * account index embedded in the token identifies which side of each trade the user was on.
 */
@Component
class LighterConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ReconstructingConnector(props.connectors.lighter, mapper) {

    override val kind = SourceKind.LIGHTER

    /** Lighter retains 12 months of history. */
    override val backfillDays = 365L
    override val windowDays = 30L
    override val pacingMs = 1000L
    override val derivePnlFromPrices = false

    private val marketMemo = Memo<Map<String, String>>(Duration.ofHours(1)) { loadMarkets() }

    override fun normalizeSymbol(raw: String): Symbol {
        val base = raw.trim().uppercase().removeSuffix("-PERP")
        return Symbol(base, "USDC")
    }

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth = Auth(headers = mapOf("Authorization" to creds.apiKey.trim()))

    override fun openExposure(creds: ExchangeCredentials): Map<String, BigDecimal>? {
        val idx = accountIndex(creds)
        val root = getJson(creds, PATH_ACCOUNT, mapOf("by" to "index", "value" to idx))
        val exposure = mutableMapOf<String, BigDecimal>()
        val markets = marketMemo.get().inverse()
        for (row in root.rows(POSITION_PATHS)) {
            val marketId = row.text(FIELD_MARKET_ID) ?: continue
            val sign = row.dec(FIELD_SIGN)?.toInt() ?: 1
            val size = row.dec(FIELD_POSITION) ?: continue
            val symbol = markets[marketId] ?: continue
            exposure[symbol] = size.multiply(BigDecimal.valueOf(sign.toLong()))
        }
        return exposure
    }

    override fun fetchFills(creds: ExchangeCredentials, start: Instant, end: Instant): List<RawFill> {
        val idx = accountIndex(creds)
        val markets = marketMemo.get()
        val startSec = start.epochSecond.toString()
        val endSec = end.epochSecond.toString()

        val trades = pageThrough<String>(maxPages = DEFAULT_MAX_PAGES, paced = true) { cursor ->
            val params = mutableMapOf(
                "account_index" to idx,
                "market_type" to "perp",
                "sort_by" to "timestamp",
                "sort_dir" to "desc",
                "limit" to PAGE_LIMIT.toString(),
                "start_timestamp" to startSec,
                "end_timestamp" to endSec,
            )
            cursor?.let { params["cursor"] = it }
            val root = getJson(creds, PATH_TRADES, params)
            val rows = root.rows(TRADE_PATHS)
            val next = root.text("next_cursor")?.takeIf { it.isNotBlank() }
            Page(rows, next)
        }.mapNotNull { mapTrade(it, idx, markets) }

        val funding = pageThrough<String>(maxPages = DEFAULT_MAX_PAGES, paced = true) { cursor ->
            val params = mutableMapOf(
                "account_index" to idx,
                "start_timestamp" to startSec,
                "end_timestamp" to endSec,
            )
            cursor?.let { params["cursor"] = it }
            val root = getJson(creds, PATH_FUNDING, params)
            val rows = root.rows(FUNDING_PATHS)
            val next = root.text("next_cursor")?.takeIf { it.isNotBlank() }
            Page(rows, next)
        }.mapNotNull { mapFunding(it, markets) }

        return (trades + funding).sortedBy { it.ts }
    }

    /** Test seam: map a Lighter `trades` body for one account, dropping rows that belong to others. */
    internal fun mapTrades(node: JsonNode, accountIndex: String, markets: Map<String, String>): List<RawFill> =
        node.rows(TRADE_PATHS).mapNotNull { mapTrade(it, accountIndex, markets) }

    private fun mapTrade(n: JsonNode, accountIndex: String, markets: Map<String, String>): RawFill? {
        val marketId = n.text(FIELD_MARKET_ID) ?: return null
        val symbol = markets[marketId] ?: return null
        val ts = n.instant(FIELD_TIMESTAMP) ?: return null
        val price = n.dec(FIELD_PRICE) ?: return null
        val qty = n.dec(FIELD_SIZE) ?: return null
        val bidId = n.text(FIELD_BID_ACCOUNT_ID) ?: return null
        val askId = n.text(FIELD_ASK_ACCOUNT_ID) ?: return null
        val isMakerAsk = n.text(FIELD_IS_MAKER_ASK)?.equals("true", ignoreCase = true) ?: false

        return when (accountIndex) {
            bidId -> RawFill(
                symbol = symbol,
                ts = ts,
                buy = true,
                price = price,
                qty = qty,
                fee = if (isMakerAsk) fee(n, FIELD_TAKER_FEE) else fee(n, FIELD_MAKER_FEE),
                realizedPnl = n.dec(FIELD_BID_ACCOUNT_PNL) ?: BigDecimal.ZERO,
            )
            askId -> RawFill(
                symbol = symbol,
                ts = ts,
                buy = false,
                price = price,
                qty = qty,
                fee = if (isMakerAsk) fee(n, FIELD_MAKER_FEE) else fee(n, FIELD_TAKER_FEE),
                realizedPnl = n.dec(FIELD_ASK_ACCOUNT_PNL) ?: BigDecimal.ZERO,
            )
            else -> null
        }
    }

    private fun mapFunding(n: JsonNode, markets: Map<String, String>): RawFill? {
        val marketId = n.text(FIELD_MARKET_ID) ?: return null
        val symbol = markets[marketId] ?: return null
        val ts = n.instant(FIELD_TIMESTAMP) ?: return null
        val amount = n.dec(FIELD_FUNDING_AMOUNT) ?: return null
        return RawFill(
            symbol = symbol,
            ts = ts,
            buy = true,
            price = BigDecimal.ZERO,
            qty = BigDecimal.ZERO,
            funding = amount,
        )
    }

    private fun fee(n: JsonNode, keys: List<String>): BigDecimal =
        n.dec(keys)?.abs() ?: BigDecimal.ZERO

    private fun loadMarkets(): Map<String, String> {
        val root = publicJson(PATH_ORDER_BOOK_DETAILS, mapOf("filter" to "perp")) ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for (row in root.rows(ORDER_BOOK_PATHS)) {
            val symbol = row.text(FIELD_SYMBOL) ?: continue
            val marketId = row.text(FIELD_MARKET_ID) ?: continue
            val marketType = row.text(FIELD_MARKET_TYPE) ?: "perp"
            if (marketType.equals("perp", ignoreCase = true)) {
                out[marketId] = symbol
            }
        }
        return out
    }

    private fun Map<String, String>.inverse(): Map<String, String> =
        entries.associate { it.value.uppercase() to it.key }

    private fun accountIndex(creds: ExchangeCredentials): String {
        val token = creds.apiKey.trim()
        if (!token.startsWith(READ_ONLY_PREFIX)) {
            throw AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = "$venue expects a read-only token starting with ro:")
        }
        return token.split(":").getOrNull(1)
            ?: throw AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = "$venue read-only token is missing the account index")
    }

    override fun adjust(record: PositionRecord): PositionRecord = record.copy(pnlCurrency = "USDC")

    private companion object {
        const val READ_ONLY_PREFIX = "ro:"
        const val PAGE_LIMIT = 100

        const val PATH_ACCOUNT = "/api/v1/account"
        const val PATH_TRADES = "/api/v1/trades"
        const val PATH_FUNDING = "/api/v1/positionFunding"
        const val PATH_ORDER_BOOK_DETAILS = "/api/v1/orderBookDetails"

        val POSITION_PATHS = listOf("positions", "account.positions")
        val TRADE_PATHS = listOf("trades")
        val FUNDING_PATHS = listOf("funding", "position_funding")
        val ORDER_BOOK_PATHS = listOf("order_book_details")

        val FIELD_MARKET_ID = listOf("market_id")
        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_MARKET_TYPE = listOf("market_type")
        val FIELD_SIGN = listOf("sign")
        val FIELD_POSITION = listOf("position")
        val FIELD_TIMESTAMP = listOf("timestamp")
        val FIELD_PRICE = listOf("price")
        val FIELD_SIZE = listOf("size")
        val FIELD_BID_ACCOUNT_ID = listOf("bid_account_id")
        val FIELD_ASK_ACCOUNT_ID = listOf("ask_account_id")
        val FIELD_IS_MAKER_ASK = listOf("is_maker_ask")
        val FIELD_MAKER_FEE = listOf("maker_fee")
        val FIELD_TAKER_FEE = listOf("taker_fee")
        val FIELD_BID_ACCOUNT_PNL = listOf("bid_account_pnl")
        val FIELD_ASK_ACCOUNT_PNL = listOf("ask_account_pnl")
        val FIELD_FUNDING_AMOUNT = listOf("amount", "usdc")
    }
}
