// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.bingx

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * BingX USDT-M perpetual futures connector. BingX exposes fills/orders rather than clean closed
 * positions, so fills are pulled over time windows and folded into flat-to-flat positions by the
 * shared [PositionReconstructor].
 *
 * Auth: signature = HMAC-SHA256(secret, sortedQueryString), appended as &signature=...; the API key
 * goes in the X-BX-APIKEY header.
 *
 * Reconstruction groups fills by (symbol, positionSide) so hedge-mode long/short legs stay separate;
 * one-way mode collapses to a single net stream.
 *
 * Verified against a real account: synced quantities, fees and computed PnL match BingX's own UI.
 * Field names are still read defensively (the API has shipped several shapes) so the mapping is
 * resilient if BingX changes a payload.
 */
@Component
class BingxConnector(
    private val props: AppProperties,
    private val mapper: ObjectMapper,
) : ApiConnector {

    private val log = LoggerFactory.getLogger(BingxConnector::class.java)
    private val client: RestClient = RestClient.builder().baseUrl(props.connectors.bingx.baseUrl).build()

    override val kind = SourceKind.BINGX

    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw.substringBefore(GROUP_SEP))

    override fun fetchClosedPositions(
        credentials: ExchangeCredentials,
        cursor: SyncCursor,
        backfillFrom: Instant?,
    ): SyncBatch {
        val now = Instant.now()

        // Reconstruction needs a FLAT starting point: flat-to-flat pairing only works if the fill
        // stream begins where net exposure was zero. Starting from the cursor (or from `sync_from`)
        // would begin mid-position — e.g. buys that were *covering a short* opened earlier look like
        // opening a long, so the position never closes and every later trade on that symbol is
        // swallowed. So we always re-fetch the full retained fill history (BingX serves ~30d) and
        // reconstruct the whole stream. `sync_from` and the cursor do NOT truncate the fetch; they
        // only decide which *reconstructed positions* to keep (below). Re-fetching every sync is
        // cheap (idempotent upsert keyed by externalId), and it also repairs positions that an
        // earlier, truncated sync mis-paired.
        //
        // Walk *backward* from now in 7-day windows until BingX stops returning fills
        // (EMPTY_WINDOW_STREAK consecutive empty windows = history exhausted / past API retention),
        // or the MAX_WINDOWS / DEFAULT_BACKFILL_DAYS safety backstop is reached.
        val hardFloor = now.minus(DEFAULT_BACKFILL_DAYS, ChronoUnit.DAYS)
        val raw = mutableListOf<RawFill>()
        var windows = 0
        var windowEnd = now
        var emptyStreak = 0
        var oldestWithData: Instant? = null
        while (windowEnd.isAfter(hardFloor) && windows < MAX_WINDOWS) {
            val windowStart = maxOf(windowEnd.minus(WINDOW_DAYS, ChronoUnit.DAYS), hardFloor)
            val before = raw.size
            try {
                raw += fetchWindow(credentials, windowStart, windowEnd)
            } catch (e: AppException) {
                // First window failing is a genuine error (auth/permission); later failures are
                // treated as reaching BingX's history retention limit — stop gracefully.
                if (windows == 0) throw e
                log.warn("BingX fetch stopped at {}: {}", windowStart, e.message)
                break
            }
            windows++
            if (raw.size > before) {
                emptyStreak = 0
                oldestWithData = windowStart
            } else {
                emptyStreak++
            }
            if (emptyStreak >= EMPTY_WINDOW_STREAK) {
                log.info(
                    "BingX fetch: history exhausted — {} empty window(s) older than {}; oldest fills at {}",
                    emptyStreak, windowStart, oldestWithData,
                )
                break
            }
            windowEnd = windowStart
            pace(windowEnd.isAfter(hardFloor) && windows < MAX_WINDOWS)
        }
        val oldest = oldestWithData ?: windowEnd

        val reconstructed = PositionReconstructor.reconstruct(raw, ::normalizeSymbol)
        // EMIT NARROW: only positions that closed after the cursor watermark (or, on first sync,
        // on/after sync_from). We FETCH the whole retained window for a correct flat start, but we
        // must NOT re-emit already-synced trades: as the window slides, an old trade's opening fills
        // age out of retention, so re-reconstructing it would start mid-position and produce a
        // corrupted variant (shifted boundary → different externalId). Freezing everything at/before
        // the watermark means those wrong re-derivations are computed but discarded here, never
        // overwriting the good stored row. To deliberately repair a source after a logic fix, reset
        // its cursor to null once — that re-emits the full window from sync_from.
        val keepAfter = cursor.lastClosedAt
        val records = reconstructed
            .filter {
                if (keepAfter != null) it.closedAt.isAfter(keepAfter)
                else backfillFrom == null || !it.closedAt.isBefore(backfillFrom)
            }
            // BingX fills carry no PnL, so derive it from leg prices (fees/funding stay as reconstructed).
            .map { it.copy(realizedPnl = PositionReconstructor.realizedFromPrices(it)) }
        val maxClosed = records.maxOfOrNull { it.closedAt } ?: cursor.lastClosedAt
        log.info(
            "BingX fetch: {} fills over {} window(s) -> {} reconstructed, {} new (cursor={}, sync_from={}, oldest requested={})",
            raw.size, windows, reconstructed.size, records.size, cursor.lastClosedAt, backfillFrom, oldest,
        )
        logOpenResidual(raw)
        return SyncBatch(records, SyncCursor(lastClosedAt = maxClosed, lastExternalId = records.lastOrNull()?.externalId))
    }

    /** One window's worth of mapped fills, with a sample logged at DEBUG for payload verification. */
    private fun fetchWindow(creds: ExchangeCredentials, start: Instant, end: Instant): List<RawFill> {
        val node = call(creds, mapOf(
            "startTs" to start.toEpochMilli().toString(),
            "endTs" to end.toEpochMilli().toString(),
        ))
        val fills = fillsArray(node)
        if (log.isDebugEnabled) {
            // TEMP DIAGNOSTIC: dump every fill (key fields) so a missing/extra leg is visible.
            log.debug("BingX window {}..{}: {} fills", start, end, fills.size)
            fills.forEach { n ->
                log.debug(
                    "  fill: symbol={} side={} posSide={} filledTm={} price={} amount={} volume={}",
                    n.path("symbol").asText(""), n.path("side").asText(""), n.path("positionSide").asText(""),
                    n.path("filledTm").asText(""), n.path("price").asText(""), n.path("amount").asText(""), n.path("volume").asText(""),
                )
            }
        }
        val mapped = ArrayList<RawFill>(fills.size)
        val skips = LinkedHashMap<String, Int>() // reason -> count
        var firstSkipSample: String? = null
        for (n in fills) {
            when (val r = mapFill(n)) {
                is FillResult.Ok -> mapped += r.fill
                is FillResult.Skip -> {
                    skips.merge(r.reason, 1, Int::plus)
                    if (firstSkipSample == null) firstSkipSample = n.toString().take(400)
                }
            }
        }
        // Dropped fills are dangerous for reconstruction (a missing mid-lifecycle leg can mis-pair
        // positions), so surface them loudly instead of swallowing them in mapNotNull.
        if (skips.isNotEmpty()) {
            log.warn(
                "BingX window {}..{}: skipped {} of {} fill(s) {} — first offender: {}",
                start, end, skips.values.sum(), fills.size, skips, firstSkipSample,
            )
        }
        return mapped
    }

    private sealed interface FillResult {
        data class Ok(val fill: RawFill) : FillResult
        data class Skip(val reason: String) : FillResult
    }

    /**
     * TEMP DIAGNOSTIC: log any (symbol, positionSide) group whose fills don't net flat. These are
     * positions the reconstructor leaves OPEN and therefore does not emit — i.e. an orphan close
     * (its opening fills weren't in the fetched window) or a genuinely still-open position. The
     * threshold is 0.1% of the larger side, far above amount/price rounding noise but far below a
     * real open leg, so a closed trade with tiny rounding residue won't be flagged.
     */
    private fun logOpenResidual(raw: List<RawFill>) {
        if (!log.isDebugEnabled) return
        raw.groupBy { it.symbol }.forEach { (group, fills) ->
            var net = BigDecimal.ZERO
            var grossBuy = BigDecimal.ZERO
            var grossSell = BigDecimal.ZERO
            for (f in fills) {
                if (f.buy) { net = net.add(f.qty); grossBuy = grossBuy.add(f.qty) }
                else { net = net.subtract(f.qty); grossSell = grossSell.add(f.qty) }
            }
            val eps = grossBuy.max(grossSell).multiply(BigDecimal("0.001"))
            if (net.abs() > eps) {
                log.debug(
                    "BingX open residual (still-open position, not emitted): group={} net={} (buy={} sell={}) fills={} firstTs={} lastTs={}",
                    group, net, grossBuy, grossSell, fills.size,
                    fills.minByOrNull { it.ts }?.ts, fills.maxByOrNull { it.ts }?.ts,
                )
            }
        }
    }

    /** Pace successive windows to stay under BingX's rate limit; [more] guards the final window. */
    private fun pace(more: Boolean) {
        if (!more) return
        try {
            Thread.sleep(WINDOW_PACING_MS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Signed GET with bounded exponential backoff on BingX rate limiting — both HTTP 429 and the
     * business code 100410 ("rate limited") are retried, since the windowed backfill makes many
     * calls in quick succession. The timestamp/signature are recomputed on each attempt.
     */
    private fun call(creds: ExchangeCredentials, params: Map<String, String>): JsonNode {
        var attempt = 0
        while (true) {
            val all = sortedMapOf<String, String>()
            all.putAll(params)
            all["timestamp"] = Instant.now().toEpochMilli().toString()
            val query = all.entries.joinToString("&") { "${it.key}=${it.value}" }
            val signature = ExchangeSign.hmacSha256Hex(creds.apiSecret, query)
            log.debug("BingX request: GET {} params={} attempt={}", PATH_FILLS, params, attempt) // never log secret/signature
            val body = try {
                client.get()
                    .uri { b ->
                        b.path(PATH_FILLS)
                        all.forEach { (k, v) -> b.queryParam(k, v) }
                        b.queryParam("signature", signature)
                        b.build()
                    }
                    .header("X-BX-APIKEY", creds.apiKey)
                    .retrieve()
                    .body(String::class.java)
            } catch (e: RestClientResponseException) {
                if (e.statusCode.value() == 429 && attempt < MAX_RETRIES) {
                    backoff(++attempt, "HTTP 429")
                    continue
                }
                log.warn("BingX HTTP error: path={} status={} body={}", PATH_FILLS, e.statusCode.value(), e.responseBodyAsString.take(500))
                throw mapHttpError(e)
            }
            val root = mapper.readTree(body ?: "{}")
            val code = root.path("code").asInt(0)
            if (code == 0) return root
            val msg = root.path("msg").asText("")
            if (code in RATE_LIMIT_CODES && attempt < MAX_RETRIES) {
                backoff(++attempt, "code=$code msg=$msg")
                continue
            }
            log.warn("BingX business error: path={} code={} msg={}", PATH_FILLS, code, msg)
            throw mapBusinessError(code, msg)
        }
    }

    /** Sleep with exponential backoff (500ms, 1s, 2s, …) between rate-limited retries. */
    private fun backoff(attempt: Int, reason: String) {
        val ms = RETRY_BASE_MS shl (attempt - 1)
        log.warn("BingX rate limited ({}), retry {}/{} after {}ms", reason, attempt, MAX_RETRIES, ms)
        try {
            Thread.sleep(ms)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AppException.badRequest("SYNC_FAILED", detail = "Interrupted during BingX backoff", cause = ie)
        }
    }

    private fun fillsArray(root: JsonNode): List<JsonNode> {
        val data = root.path("data")
        val arr = when {
            data.isArray -> data
            data.path("fill_orders").isArray -> data.path("fill_orders")
            data.path("fillOrders").isArray -> data.path("fillOrders")
            data.path("orders").isArray -> data.path("orders")
            else -> return emptyList()
        }
        return arr.toList()
    }

    private fun mapFill(n: JsonNode): FillResult {
        val symbol = n.text(FIELD_SYMBOL) ?: return FillResult.Skip("no symbol")
        val ts = parseFillTime(n) ?: return FillResult.Skip("no timestamp")
        val side = n.text(FIELD_SIDE)?.uppercase() ?: return FillResult.Skip("no side")
        val price = n.dec(FIELD_PRICE) ?: return FillResult.Skip("no price")
        // BingX fills carry no base-quantity field — `volume` is scaled (×1000 vs the exchange UI).
        // Derive the true base qty from the USDT notional `amount` ÷ price, which matches the UI.
        val notional = n.dec(FIELD_AMOUNT)
        val qty = if (notional != null && price.signum() > 0) notional.divide(price, MC)
        else n.dec(FIELD_QTY) ?: BigDecimal.ZERO
        val positionSide = n.text(FIELD_POSITION_SIDE)?.uppercase()
        val groupKey = if (positionSide != null) "$symbol$GROUP_SEP$positionSide" else symbol
        return FillResult.Ok(
            RawFill(
                symbol = groupKey,
                ts = ts,
                buy = side == "BUY",
                price = price,
                qty = qty,
                fee = (n.dec(FIELD_FEE) ?: BigDecimal.ZERO).abs(),
                realizedPnl = BigDecimal.ZERO, // BingX fills omit PnL; computed from leg prices post-reconstruction
            )
        )
    }

    /**
     * Robustly read a fill timestamp. BingX has returned this both as an epoch number (seconds *or*
     * milliseconds, sometimes as a numeric string) and as an ISO-8601 datetime string depending on
     * the field/endpoint. The previous `asLong()` returned 0 for string datetimes — silently dating
     * every position to 1970 — so we now handle all three shapes and skip the fill if none parse.
     */
    private fun parseFillTime(n: JsonNode): Instant? {
        for (k in FIELD_TIME) {
            val node = n.path(k)
            if (node.isMissingNode || node.isNull) continue
            if (node.isNumber) return epochToInstant(node.asLong())
            val s = node.asText().trim()
            if (s.isEmpty()) continue
            s.toLongOrNull()?.let { return epochToInstant(it) }
            runCatching { return Instant.parse(s) }
            runCatching { return OffsetDateTime.parse(s).toInstant() }
        }
        return null
    }

    /** Treat values below 10^12 as epoch seconds, otherwise milliseconds (10^12ms ≈ year 2001). */
    private fun epochToInstant(v: Long): Instant =
        if (v < 1_000_000_000_000L) Instant.ofEpochSecond(v) else Instant.ofEpochMilli(v)

    private fun mapBusinessError(code: Int, msg: String): AppException {
        val detail = "BingX code=$code msg=$msg"
        return when (code) {
            in AUTH_CODES -> AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = detail)
            in PERMISSION_CODES -> AppException.badRequest("DATA_SOURCE_PERMISSION_DENIED", detail = detail)
            in RATE_LIMIT_CODES -> AppException.tooManyRequests("SYNC_RATE_LIMITED")
            else -> AppException.badRequest("SYNC_FAILED", detail = detail)
        }
    }

    private fun mapHttpError(e: RestClientResponseException): AppException {
        val detail = "BingX HTTP ${e.statusCode.value()}: ${e.responseBodyAsString.take(300)}"
        return when (e.statusCode.value()) {
            401 -> AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = detail, cause = e)
            403 -> AppException.badRequest("DATA_SOURCE_PERMISSION_DENIED", detail = detail, cause = e)
            else -> AppException.badRequest("SYNC_FAILED", detail = detail, cause = e)
        }
    }

    private companion object {
        const val PATH_FILLS = "/openApi/swap/v2/trade/allFillOrders"
        const val WINDOW_DAYS = 7L
        const val DEFAULT_BACKFILL_DAYS = 90L
        const val MAX_WINDOWS = 320 // safety backstop (~6yr of 7-day windows); the empty-streak normally stops first
        const val EMPTY_WINDOW_STREAK = 8 // consecutive empty 7-day windows (~2mo gap) → history exhausted

        // Rate-limit handling: BingX throttles the windowed backfill (code 100410). Pace the
        // windows and retry a rate-limited call with exponential backoff before giving up.
        const val WINDOW_PACING_MS = 250L
        const val MAX_RETRIES = 4
        const val RETRY_BASE_MS = 500L
        val RATE_LIMIT_CODES = setOf(100410) // BingX "rate limited"
        const val GROUP_SEP = ' '

        val MC = MathContext(34, RoundingMode.HALF_EVEN) // for notional ÷ price → base qty

        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_AMOUNT = listOf("amount") // fill notional in quote (USDT)
        val FIELD_SIDE = listOf("side")
        val FIELD_POSITION_SIDE = listOf("positionSide")
        val FIELD_PRICE = listOf("price", "avgPrice", "fillPrice")
        val FIELD_QTY = listOf("qty", "volume", "amount", "executedQty")
        val FIELD_FEE = listOf("commission", "fee")
        val FIELD_TIME = listOf("filledTm", "filledTime", "time", "tradeTime", "updateTime", "ctime")

        val AUTH_CODES = setOf(100413, 100001) // signature / api-key errors (verify)
        val PERMISSION_CODES = setOf(100419, 100403) // permission denied (verify)
    }
}

private fun JsonNode.text(keys: List<String>): String? =
    keys.firstNotNullOfOrNull { k -> path(k).takeIf { it.isValueNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() } }

private fun JsonNode.dec(keys: List<String>): BigDecimal? =
    keys.firstNotNullOfOrNull { k ->
        path(k).takeIf { it.isValueNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull()
    }
