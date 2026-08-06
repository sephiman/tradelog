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
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.SortedMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Kraken spot — a different account and balance from [KrakenFuturesConnector], and a different signing
 * scheme: base64(HMAC-SHA512(b64decode(secret), uriPath + SHA256(nonce + postData))) over a POST body.
 * Reconstructed from trades: real fees but no PnL, no funding, and PnL in the pair's quote currency.
 */
@Component
class KrakenSpotConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ReconstructingConnector(props.connectors.krakenSpot, mapper) {

    override val kind = SourceKind.KRAKEN_SPOT

    // History is effectively unbounded, so the walk relies on the empty-window streak to stop.
    override val windowDays = 30L
    override val backfillDays = 3650L
    override val maxWindows = 130
    override val emptyWindowStreak = 6 // ~6 months of no trading before assuming the history is done

    // Metered by a decaying call counter, not a fixed rate: pace, and let the backoff handle the rest.
    override val pacingMs = 1500L

    /** Spot trades carry no PnL, so it comes from the volume-weighted leg prices. */
    override val derivePnlFromPrices = true

    override val authCodes = setOf("EAPI:Invalid key", "EAPI:Invalid signature", "EAPI:Invalid nonce")
    override val permissionCodes = setOf("EGeneral:Permission denied", "EAPI:Invalid permissions")
    override val rateLimitCodes = setOf("EAPI:Rate limit exceeded", "EGeneral:Too many requests")

    /** Kraken's nonce must strictly increase; epoch millis alone can repeat inside one millisecond. */
    private val nonce = AtomicLong(0)

    /** `XXBTZUSD` → BTC/USD. Kraken's own pair listing is the only reliable source for this. */
    private val pairs = Memo(Duration.ofHours(12)) { mapPairs(publicJson(PATH_ASSET_PAIRS)) }

    override fun normalizeSymbol(raw: String): Symbol = symbolFor(raw, pairs.get())

    /** Test seam: build the pair map from a raw `AssetPairs` body. */
    internal fun mapPairs(root: JsonNode?): Map<String, Symbol> {
        val out = HashMap<String, Symbol>()
        root?.path("result")?.properties()?.forEach { (key, pair) ->
            val symbol = symbolOf(pair) ?: return@forEach
            out[key.uppercase()] = symbol
            pair.text(FIELD_ALTNAME)?.let { out[it.uppercase()] = symbol }
        }
        log.debug("Kraken spot: {} pairs mapped", out.size)
        return out
    }

    /** Test seam: resolve one pair against a known map, falling back when it is not listed. */
    internal fun symbolFor(raw: String, known: Map<String, Symbol>): Symbol =
        known[raw.uppercase()] ?: fallbackSymbol(raw)

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        // The nonce is part of the signed body, so it goes into the map the base class will send.
        val n = nonce.updateAndGet { previous -> maxOf(previous + 1, Instant.now().toEpochMilli()) }.toString()
        query["nonce"] = n
        val postData = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        val secret = try {
            Base64.getDecoder().decode(creds.apiSecret.trim())
        } catch (e: IllegalArgumentException) {
            throw AppException.badRequest(
                "DATA_SOURCE_CREDENTIALS_INVALID",
                detail = "Kraken spot secret is not valid base64",
                cause = e,
            )
        }
        val message = path.toByteArray(Charsets.UTF_8) + ExchangeSign.sha256(n + postData)
        return Auth(
            headers = mapOf(
                "API-Key" to creds.apiKey,
                "API-Sign" to Base64.getEncoder().encodeToString(ExchangeSign.hmacSha512(secret, message)),
            ),
        )
    }

    /** Spot PnL is in the pair's quote asset, which on Kraken is usually USD or EUR rather than USDT. */
    override fun adjust(record: PositionRecord): PositionRecord =
        record.copy(pnlCurrency = record.symbol.quote)

    override fun checkEnvelope(root: JsonNode, path: String) {
        val errors = root.path("error")
        if (!errors.isArray || errors.isEmpty) return
        val first = errors.first().asText("")
        failEnvelope(path, first, errors.joinToString("; ") { it.asText("") })
    }

    /** POST-only, paged by `ofs`, and `result.trades` is an object keyed by trade id, not an array. */
    override fun fetchFills(creds: ExchangeCredentials, start: Instant, end: Instant): List<RawFill> {
        val rows = mutableListOf<JsonNode>()
        var offset = 0
        var page = 0
        while (page < MAX_PAGES) {
            val root = postForm(
                creds,
                PATH_TRADES,
                sortedMapOf(
                    "start" to start.epochSecond.toString(),
                    "end" to end.epochSecond.toString(),
                    "ofs" to offset.toString(),
                ),
            )
            val batch = root.objectValues(ROW_PATHS)
            if (batch.isEmpty()) break
            rows += batch
            page++
            if (batch.size < PAGE_LIMIT) break
            offset += batch.size
            pace()
        }
        val skips = SkipTally()
        val mapped = mutableListOf<RawFill>()
        for (row in rows) skips.keep(row, mapTrade(row), mapped)
        skips.report(log, venue, "in window $start..$end", rows.size)
        return mapped
    }

    /** Test seam: map a raw `TradesHistory` body into fills, dropping unmappable rows. */
    internal fun mapTrades(node: JsonNode): List<RawFill> =
        node.objectValues(ROW_PATHS).mapNotNull { (mapTrade(it) as? Mapped.Ok)?.value }

    private fun mapTrade(n: JsonNode): Mapped<RawFill> {
        val pair = n.text(FIELD_PAIR) ?: return Mapped.Skip("no pair")
        val ts = n.instant(FIELD_TIME) ?: return Mapped.Skip("no timestamp")
        val price = n.dec(FIELD_PRICE) ?: return Mapped.Skip("no price")
        val vol = n.dec(FIELD_VOL) ?: return Mapped.Skip("no vol")
        val type = n.text(FIELD_TYPE)?.lowercase() ?: return Mapped.Skip("no type")
        if (type != "buy" && type != "sell") return Mapped.Skip("bad type=$type")
        return Mapped.Ok(
            RawFill(
                symbol = pair,
                ts = ts,
                buy = type == "buy",
                price = price,
                qty = vol,
                fee = (n.dec(FIELD_FEE) ?: BigDecimal.ZERO).abs(),
                realizedPnl = BigDecimal.ZERO, // derived from the leg prices after reconstruction
            ),
        )
    }

    /** `wsname` ("XBT/USD") is already clean; `base`/`quote` need the legacy prefixes stripped. */
    private fun symbolOf(pair: JsonNode): Symbol? {
        pair.text(FIELD_WSNAME)?.split('/')?.takeIf { it.size == 2 }?.let { (base, quote) ->
            return Symbol(asset(base), asset(quote))
        }
        val base = pair.text(FIELD_BASE) ?: return null
        val quote = pair.text(FIELD_QUOTE) ?: return null
        return Symbol(asset(base), asset(quote))
    }

    /** XBT→BTC must happen BEFORE the split: `XBTUSD` ends with `TUSD` and would read as XB/TUSD. */
    private fun fallbackSymbol(raw: String): Symbol {
        val s = raw.trim().uppercase()
        val normalized = if (s.startsWith(XBT)) BTC + s.removePrefix(XBT) else s
        val split = Symbols.split(normalized)
        return Symbol(asset(split.base), asset(split.quote))
    }

    /** Drops Kraken's legacy `X`/`Z` asset prefix (`XXBT`, `ZUSD`), then `XBT` becomes `BTC`. */
    private fun asset(raw: String): String {
        val s = raw.trim().uppercase()
        val bare = if (s.length == 4 && (s[0] == 'X' || s[0] == 'Z')) s.substring(1) else s
        return if (bare == XBT) BTC else bare
    }

    private companion object {
        const val PATH_TRADES = "/0/private/TradesHistory"
        const val PATH_ASSET_PAIRS = "/0/public/AssetPairs"
        const val PAGE_LIMIT = 50 // TradesHistory's default and effective page size
        const val MAX_PAGES = 100
        const val XBT = "XBT"
        const val BTC = "BTC"

        val ROW_PATHS = listOf("result.trades")
        val FIELD_PAIR = listOf("pair")
        val FIELD_TIME = listOf("time")
        val FIELD_TYPE = listOf("type")
        val FIELD_PRICE = listOf("price")
        val FIELD_VOL = listOf("vol")
        val FIELD_FEE = listOf("fee")
        val FIELD_ALTNAME = listOf("altname")
        val FIELD_WSNAME = listOf("wsname")
        val FIELD_BASE = listOf("base")
        val FIELD_QUOTE = listOf("quote")
    }
}
