// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.hyperliquid

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * Hyperliquid perpetuals. Read-only history is queried by wallet address, with no key or signature.
 * Positions are reconstructed from fills; funding payments are tracked separately as zero-qty fills.
 */
@Component
class HyperliquidConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ReconstructingConnector(props.connectors.hyperliquid, mapper) {

    override val kind = SourceKind.HYPERLIQUID

    /** Hyperliquid serves only the 10,000 most recent fills; 90 days is a safe backstop. */
    override val backfillDays = 90L
    override val windowDays = 7L
    override val pacingMs = 250L

    /** Fills carry per-leg realized PnL, so nothing is derived from prices. */
    override val derivePnlFromPrices = false

    override fun normalizeSymbol(raw: String): Symbol {
        val base = raw.trim().uppercase().substringAfterLast(':').removeSuffix("-PERP")
        return Symbol(base, "USDC")
    }

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth = Auth()

    override fun openExposure(creds: ExchangeCredentials): Map<String, BigDecimal> {
        val address = requireAddress(creds)
        val root = postInfo(address, "clearinghouseState")
        val exposure = mutableMapOf<String, BigDecimal>()
        for (row in root.rows(OPEN_POSITION_PATHS)) {
            val position = row.path("position")
            val coin = position.text(FIELD_COIN) ?: continue
            val szi = position.dec(FIELD_SZI) ?: continue
            exposure[coin] = szi
        }
        log.debug("{} open exposure: {}", venue, exposure)
        return exposure
    }

    override fun fetchFills(creds: ExchangeCredentials, start: Instant, end: Instant): List<RawFill> {
        val address = requireAddress(creds)
        val fills = fetchAll(address, "userFillsByTime", start, end, FILLS_PAGE_LIMIT) { page ->
            page.mapNotNull { (mapFill(it) as? Mapped.Ok)?.value }
        }
        val funding = fetchAll(address, "userFunding", start, end, FUNDING_PAGE_LIMIT) { page ->
            page.mapNotNull { mapFunding(it) }
        }
        return (fills + funding).sortedBy { it.ts }
    }

    /** Page forward by startTime until a page is short or the window is exhausted. */
    private fun <T> fetchAll(
        address: String,
        type: String,
        start: Instant,
        end: Instant,
        pageLimit: Int,
        transform: (List<JsonNode>) -> List<T>,
    ): List<T> {
        val out = mutableListOf<T>()
        var pageStart = start.toEpochMilli()
        val endMs = end.toEpochMilli()
        while (pageStart <= endMs) {
            val rows = postInfo(address, type, pageStart, endMs).rows("")
            out += transform(rows)
            if (rows.size < pageLimit) break
            val lastTs = rows.mapNotNull { it.instant(FIELD_TIME) }.maxOrNull() ?: break
            val nextStart = lastTs.toEpochMilli()
            if (nextStart <= pageStart) break
            pageStart = nextStart + 1
            pace()
        }
        return out
    }

    private fun postInfo(address: String, type: String, startTime: Long, endTime: Long): JsonNode {
        val body = mapOf(
            "type" to type,
            "user" to address,
            "startTime" to startTime,
            "endTime" to endTime,
        )
        return postJson(ExchangeCredentials(address, ""), PATH_INFO, body)
    }

    private fun postInfo(address: String, type: String): JsonNode =
        postJson(ExchangeCredentials(address, ""), PATH_INFO, mapOf("type" to type, "user" to address))

    /** Test seam: map a `userFillsByTime` body into fills, dropping spot and unmappable rows. */
    internal fun mapUserFills(node: JsonNode): List<RawFill> =
        node.rows(listOf("")).mapNotNull { (mapFill(it) as? Mapped.Ok)?.value }

    /** Test seam: map a `userFunding` body into zero-quantity funding fills. */
    internal fun mapFundingPayments(node: JsonNode): List<RawFill> =
        node.rows(listOf("")).mapNotNull { mapFunding(it) }

    private fun mapFill(n: JsonNode): Mapped<RawFill> {
        val coin = n.text(FIELD_COIN) ?: return Mapped.Skip("no coin")
        if (isSpot(coin)) return Mapped.Skip("spot fill")
        val ts = n.instant(FIELD_TIME) ?: return Mapped.Skip("no timestamp")
        val side = n.text(FIELD_SIDE) ?: return Mapped.Skip("no side")
        val dir = n.text(FIELD_DIR) ?: ""
        val price = n.dec(FIELD_PRICE) ?: return Mapped.Skip("no price")
        val qty = n.dec(FIELD_SIZE) ?: return Mapped.Skip("no size")
        val fee = (n.dec(FIELD_FEE) ?: BigDecimal.ZERO).abs()
            .add((n.dec(FIELD_BUILDER_FEE) ?: BigDecimal.ZERO).abs())
        return Mapped.Ok(
            RawFill(
                symbol = coin,
                ts = ts,
                buy = side == SIDE_BUY,
                price = price,
                qty = qty,
                fee = fee,
                realizedPnl = n.dec(FIELD_CLOSED_PNL) ?: BigDecimal.ZERO,
                reduces = dir.startsWith(DIR_CLOSE),
            ),
        )
    }

    private fun mapFunding(n: JsonNode): RawFill? {
        val delta = n.path("delta")
        val coin = delta.text(FIELD_COIN) ?: return null
        if (isSpot(coin)) return null
        val ts = n.instant(FIELD_TIME) ?: return null
        val amount = delta.dec(FIELD_USDC) ?: return null
        return RawFill(
            symbol = coin,
            ts = ts,
            buy = true,
            price = BigDecimal.ZERO,
            qty = BigDecimal.ZERO,
            funding = amount.negate(),
        )
    }

    private fun isSpot(coin: String): Boolean = coin.contains("/") || coin.startsWith("@")

    private fun requireAddress(creds: ExchangeCredentials): String {
        val address = creds.apiKey.trim()
        if (!ADDRESS_REGEX.matches(address)) {
            throw AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = "$venue expects a 42-character 0x wallet address")
        }
        return address
    }

    private companion object {
        const val PATH_INFO = "/info"
        const val FILLS_PAGE_LIMIT = 2000
        const val FUNDING_PAGE_LIMIT = 500
        const val SIDE_BUY = "B"
        const val DIR_CLOSE = "Close"

        val ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{40}$")

        val OPEN_POSITION_PATHS = listOf("assetPositions")
        val FIELD_COIN = listOf("coin")
        val FIELD_SZI = listOf("szi")
        val FIELD_TIME = listOf("time")
        val FIELD_SIDE = listOf("side")
        val FIELD_DIR = listOf("dir")
        val FIELD_PRICE = listOf("px")
        val FIELD_SIZE = listOf("sz")
        val FIELD_FEE = listOf("fee")
        val FIELD_BUILDER_FEE = listOf("builderFee")
        val FIELD_CLOSED_PNL = listOf("closedPnl")
        val FIELD_USDC = listOf("usdc")
    }
}
