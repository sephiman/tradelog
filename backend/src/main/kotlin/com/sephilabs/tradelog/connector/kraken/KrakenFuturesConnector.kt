// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.kraken

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import java.util.SortedMap

/**
 * Kraken Futures — a separate platform from Kraken spot, with its own host, account and signing.
 * Linear (`PF_`) perpetuals only. No fee is recorded: Kraken states its API fee values no longer
 * reflect what was charged, and `realized_pnl` is null on any paged request, so PnL comes from prices.
 */
@Component
class KrakenFuturesConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ReconstructingConnector(props.connectors.kraken, mapper) {

    override val kind = SourceKind.KRAKEN_FUTURES

    /** `PF_XBTUSD` → BTC/USD: drop the contract-type prefix, then undo Kraken's `XBT` for bitcoin. */
    override fun normalizeSymbol(raw: String): Symbol {
        val bare = raw.uppercase().substringAfter(PREFIX_SEP, raw.uppercase())
        return Symbols.split(bare.replace(XBT, BTC))
    }

    // /fills has no time range: it is paged backward instead. One window covers the whole walk, and the
    // paging inside fetchFills does the rest.
    override val backfillDays = SINGLE_WINDOW_DAYS
    override val windowDays = SINGLE_WINDOW_DAYS
    override val pacingMs = 300L

    /** `realized_pnl` is null on any paged request, so PnL comes from the leg prices instead. */
    override val derivePnlFromPrices = true

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        val postData = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        val nonce = Instant.now().toEpochMilli().toString()
        // Kraken signs the path with any leading `/derivatives` removed — the history endpoints have no
        // such prefix and are signed exactly as requested.
        val signedPath = path.removePrefix(DERIVATIVES_PREFIX)
        val secret = try {
            Base64.getDecoder().decode(creds.apiSecret.trim())
        } catch (e: IllegalArgumentException) {
            // Kraken issues the secret base64-encoded; a value that will not decode is the wrong secret,
            // and saying so beats a signature error the user cannot interpret.
            throw AppException.badRequest(
                "DATA_SOURCE_CREDENTIALS_INVALID",
                detail = "Kraken Futures secret is not valid base64",
                cause = e,
            )
        }
        val authent = Base64.getEncoder().encodeToString(
            ExchangeSign.hmacSha512(secret, ExchangeSign.sha256(postData + nonce + signedPath)),
        )
        return Auth(
            headers = mapOf(
                "APIKey" to creds.apiKey,
                "Nonce" to nonce,
                "Authent" to authent,
            ),
        )
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        val result = root.text(FIELD_RESULT)
        if (result == null || result.equals(RESULT_SUCCESS, ignoreCase = true)) return
        failEnvelope(path, root.text(FIELD_ERROR) ?: result, root.path("error").asText(""))
    }

    /** Pages backward: each page's oldest `fillTime` is the next cursor. Stops if it stops advancing. */
    override fun fetchFills(creds: ExchangeCredentials, start: Instant, end: Instant): List<RawFill> {
        val rows = mutableListOf<JsonNode>()
        var lastFillTime: String? = null
        var page = 0
        var oldestSeen: Instant? = null
        while (page < MAX_PAGES) {
            val params = sortedMapOf<String, String>()
            lastFillTime?.let { params["lastFillTime"] = it }
            val batch = getJson(creds, PATH_FILLS, params).rows(ROW_PATHS)
            if (batch.isEmpty()) break
            rows += batch
            page++
            val oldest = batch.mapNotNull { it.instant(FIELD_TIME) }.minOrNull()
            // No parseable timestamp, or one that has stopped moving, means there is no safe next cursor.
            if (oldest == null || (oldestSeen != null && !oldest.isBefore(oldestSeen))) break
            oldestSeen = oldest
            if (oldest.isBefore(start)) break
            if (batch.size < PAGE_LIMIT) break
            lastFillTime = batch.mapNotNull { it.text(FIELD_TIME) }.minOrNull()
            pace()
        }
        if (page >= MAX_PAGES) {
            log.warn("Kraken Futures: stopped after the {}-page cap with more fills available", MAX_PAGES)
        }
        val skips = SkipTally()
        val mapped = mutableListOf<RawFill>()
        for (row in rows) skips.keep(row, mapFill(row), mapped)
        skips.report(log, venue, "over $page page(s) back to ${oldestSeen ?: start}", rows.size)
        return mapped
    }

    /** Test seam: map a raw `/fills` body into fills, dropping unmappable rows. */
    internal fun mapFills(node: JsonNode): List<RawFill> =
        node.rows(ROW_PATHS).mapNotNull { (mapFill(it) as? Mapped.Ok)?.value }

    private fun mapFill(n: JsonNode): Mapped<RawFill> {
        val symbol = n.text(FIELD_SYMBOL) ?: return Mapped.Skip("no symbol")
        if (!symbol.uppercase().startsWith(LINEAR_PREFIX)) return Mapped.Skip("not a linear perpetual ($symbol)")
        val ts = n.instant(FIELD_TIME) ?: return Mapped.Skip("no timestamp")
        val price = n.dec(FIELD_PRICE) ?: return Mapped.Skip("no price")
        val size = n.dec(FIELD_SIZE) ?: return Mapped.Skip("no size")
        val side = n.text(FIELD_SIDE)?.lowercase() ?: return Mapped.Skip("no side")
        return Mapped.Ok(
            RawFill(
                symbol = symbol,
                ts = ts,
                buy = side == "buy",
                price = price,
                qty = size,
                // Kraken states these endpoints' fee values no longer reflect what was charged, so no
                // fee is recorded at all rather than one the exchange itself disowns.
                fee = BigDecimal.ZERO,
                realizedPnl = BigDecimal.ZERO, // derived from the leg prices after reconstruction
            ),
        )
    }

    private companion object {
        const val PATH_FILLS = "/derivatives/api/v3/fills"
        const val DERIVATIVES_PREFIX = "/derivatives"
        const val PAGE_LIMIT = 100 // fixed page size; there is no limit parameter
        const val MAX_PAGES = 200
        const val SINGLE_WINDOW_DAYS = 730L

        const val RESULT_SUCCESS = "success"

        /** Multi-collateral linear perpetuals, whose size is a base amount and PnL is quote-settled. */
        const val LINEAR_PREFIX = "PF_"
        const val PREFIX_SEP = "_"
        const val XBT = "XBT"
        const val BTC = "BTC"

        val ROW_PATHS = listOf("fills")
        val FIELD_RESULT = listOf("result")
        val FIELD_ERROR = listOf("error")
        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_SIDE = listOf("side")
        val FIELD_PRICE = listOf("price")
        val FIELD_SIZE = listOf("size")
        val FIELD_TIME = listOf("fillTime")
    }
}
