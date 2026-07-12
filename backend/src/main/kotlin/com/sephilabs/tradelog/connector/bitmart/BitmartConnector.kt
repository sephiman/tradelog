// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.bitmart

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
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * BitMart USDT-M perpetual futures connector. Like BingX, BitMart exposes fills/trades rather than
 * clean closed positions, so fills are pulled over time windows and folded into flat-to-flat
 * positions by the shared [PositionReconstructor].
 *
 * Auth: the private contract reads (`/contract/private/trades`) are **KEYED**, not signed — BitMart's
 * own SDK sends only the `X-BM-KEY: <apiKey>` header, with no signature, timestamp or memo. So this
 * connector is simpler than BingX: a plain GET with one header. Only the API key is required; the
 * stored secret/passphrase are unused for these endpoints.
 *
 * BitMart fills carry `realised_profit` and `paid_fees`, so PnL/fees are taken directly from the
 * payload (no price-derivation as BingX needs). `vol` is denominated in contracts; the displayed base
 * quantity is `vol × contract_size` from the public `/contract/public/details` endpoint. Because every
 * fill of a symbol shares the same unit, reconstruction (net-zero detection, volume-weighted avg
 * prices) is unit-invariant — so the contract-size scaling only affects the displayed quantity and can
 * never corrupt pairing or PnL.
 *
 * Verified against BitMart's official Python SDK; field names are read defensively so the mapping is
 * resilient if a payload shape shifts. Side codes (1=buy_open_long, 2=buy_close_short,
 * 3=sell_close_long, 4=sell_open_short) reduce to buy={1,2}/sell={3,4} in both hedge and one-way mode.
 */
@Component
class BitmartConnector(
    private val props: AppProperties,
    private val mapper: ObjectMapper,
) : ApiConnector {

    private val log = LoggerFactory.getLogger(BitmartConnector::class.java)
    private val client: RestClient = ExchangeHttp.restClient(props.connectors.bitmart)

    override val kind = SourceKind.BITMART

    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw.substringBefore(GROUP_SEP))

    override fun fetchClosedPositions(
        credentials: ExchangeCredentials,
        cursor: SyncCursor,
        backfillFrom: Instant?,
    ): SyncBatch {
        val now = Instant.now()
        val contractSizes = fetchContractSizes()

        // Reconstruction needs a FLAT starting point, so — exactly as in BingX — we always re-fetch the
        // full retained fill history and reconstruct the whole stream; `sync_from`/`cursor` only decide
        // which reconstructed positions to *emit*, not what to fetch. Walk backward from now in 7-day
        // windows (well under BitMart's 90-day max interval; keeps each response near the 200-row cap)
        // until BitMart stops returning fills or the backfill backstop is reached.
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
                raw += fetchWindow(credentials, windowStart, windowEnd, contractSizes)
            } catch (e: AppException) {
                // First window failing is a genuine error (auth/permission); later failures are treated
                // as reaching BitMart's history retention limit — stop gracefully.
                if (windows == 0) throw e
                log.warn("BitMart fetch stopped at {}: {}", windowStart, e.message)
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
                    "BitMart fetch: history exhausted — {} empty window(s) older than {}; oldest fills at {}",
                    emptyStreak, windowStart, oldestWithData,
                )
                break
            }
            windowEnd = windowStart
            pace(windowEnd.isAfter(hardFloor) && windows < MAX_WINDOWS)
        }
        val oldest = oldestWithData ?: windowEnd

        val reconstructed = PositionReconstructor.reconstruct(raw, ::normalizeSymbol)
        // EMIT NARROW: only positions that closed after the cursor watermark (or, on first sync, on/after
        // sync_from). Same rationale as BingX — re-deriving an already-synced position whose opening
        // fills aged out of retention would corrupt it, so freeze everything at/before the watermark.
        val keepAfter = cursor.lastClosedAt
        val records = reconstructed
            .filter {
                if (keepAfter != null) it.closedAt.isAfter(keepAfter)
                else backfillFrom == null || !it.closedAt.isBefore(backfillFrom)
            }
        val maxClosed = records.maxOfOrNull { it.closedAt } ?: cursor.lastClosedAt
        log.info(
            "BitMart fetch: {} fills over {} window(s) -> {} reconstructed, {} new (cursor={}, sync_from={}, oldest requested={})",
            raw.size, windows, reconstructed.size, records.size, cursor.lastClosedAt, backfillFrom, oldest,
        )
        return SyncBatch(records, SyncCursor(lastClosedAt = maxClosed, lastExternalId = records.lastOrNull()?.externalId))
    }

    /**
     * One window's worth of mapped fills. `/contract/private/trades` returns at most 200 rows with no
     * paging parameter, so a window that comes back at exactly the cap is assumed truncated and split
     * in half recursively until each sub-window is under the cap (or a min-span floor is hit, logged).
     */
    private fun fetchWindow(
        creds: ExchangeCredentials,
        start: Instant,
        end: Instant,
        contractSizes: Map<String, BigDecimal>,
    ): List<RawFill> {
        val node = call(creds, start, end)
        val trades = tradesArray(node)
        if (trades.size >= PAGE_LIMIT && end.toEpochMilli() - start.toEpochMilli() > MIN_SPLIT_MS) {
            val mid = Instant.ofEpochMilli((start.toEpochMilli() + end.toEpochMilli()) / 2)
            log.info("BitMart window {}..{} hit the {}-row cap; splitting at {}", start, end, PAGE_LIMIT, mid)
            pace(true)
            // Older half first keeps the overall walk strictly backward in time.
            return fetchWindow(creds, start, mid, contractSizes) + fetchWindow(creds, mid, end, contractSizes)
        }
        if (trades.size >= PAGE_LIMIT) {
            log.warn(
                "BitMart window {}..{} returned {} rows at the min split span — some fills may be dropped",
                start, end, trades.size,
            )
        }
        val mapped = ArrayList<RawFill>(trades.size)
        val skips = LinkedHashMap<String, Int>() // reason -> count
        var firstSkipSample: String? = null
        for (n in trades) {
            when (val r = mapFill(n, contractSizes)) {
                is FillResult.Ok -> mapped += r.fill
                is FillResult.Skip -> {
                    skips.merge(r.reason, 1, Int::plus)
                    if (firstSkipSample == null) firstSkipSample = n.toString().take(400)
                }
            }
        }
        // A dropped mid-lifecycle leg can mis-pair positions, so surface skips loudly.
        if (skips.isNotEmpty()) {
            log.warn(
                "BitMart window {}..{}: skipped {} of {} fill(s) {} — first offender: {}",
                start, end, skips.values.sum(), trades.size, skips, firstSkipSample,
            )
        }
        return mapped
    }

    private sealed interface FillResult {
        data class Ok(val fill: RawFill) : FillResult
        data class Skip(val reason: String) : FillResult
    }

    /** Test seam: map a raw `/contract/private/trades` response body into [RawFill]s, dropping unmappable rows. */
    internal fun mapTrades(node: JsonNode, contractSizes: Map<String, BigDecimal>): List<RawFill> =
        tradesArray(node).mapNotNull { (mapFill(it, contractSizes) as? FillResult.Ok)?.fill }

    private fun mapFill(n: JsonNode, contractSizes: Map<String, BigDecimal>): FillResult {
        val symbol = n.text(FIELD_SYMBOL) ?: return FillResult.Skip("no symbol")
        val ts = parseFillTime(n) ?: return FillResult.Skip("no timestamp")
        val side = n.path(FIELD_SIDE).takeIf { it.isInt || it.canConvertToInt() }?.asInt()
            ?: n.text(FIELD_SIDE)?.toIntOrNull()
            ?: return FillResult.Skip("no side")
        val price = n.dec(FIELD_PRICE) ?: return FillResult.Skip("no price")
        val vol = n.dec(FIELD_VOL) ?: return FillResult.Skip("no vol")
        // Side codes: 1=buy_open_long, 2=buy_close_short, 3=sell_close_long, 4=sell_open_short.
        // Holds in both hedge and one-way mode: {1,2}=buy, {3,4}=sell.
        if (side !in 1..4) return FillResult.Skip("bad side=$side")
        val buy = side == 1 || side == 2
        val contractSize = contractSizes[symbol.uppercase()] ?: BigDecimal.ONE
        return FillResult.Ok(
            RawFill(
                symbol = symbol,
                ts = ts,
                buy = buy,
                price = price,
                qty = vol.multiply(contractSize),
                fee = (n.dec(FIELD_FEE) ?: BigDecimal.ZERO).abs(),
                realizedPnl = n.dec(FIELD_PNL) ?: BigDecimal.ZERO,
                // Funding is not part of /contract/private/trades; left zero (could be added later from
                // /contract/private/transaction-history flow_type=3).
                funding = BigDecimal.ZERO,
            )
        )
    }

    /** `create_time` is epoch milliseconds; tolerate seconds too via [epochToInstant] just in case. */
    private fun parseFillTime(n: JsonNode): Instant? {
        for (k in FIELD_TIME) {
            val node = n.path(k)
            if (node.isMissingNode || node.isNull) continue
            if (node.isNumber) return epochToInstant(node.asLong())
            val s = node.asText().trim()
            if (s.isEmpty()) continue
            s.toLongOrNull()?.let { return epochToInstant(it) }
            runCatching { return Instant.parse(s) }
        }
        return null
    }

    /** Treat values below 10^12 as epoch seconds, otherwise milliseconds (10^12ms ≈ year 2001). */
    private fun epochToInstant(v: Long): Instant =
        if (v < 1_000_000_000_000L) Instant.ofEpochSecond(v) else Instant.ofEpochMilli(v)

    /**
     * Map of `SYMBOL -> contract_size` (base currency per contract) from the public, unauthenticated
     * details endpoint. Best-effort: a failure or a missing symbol just falls back to a size of 1, which
     * (per the class doc) only scales the displayed quantity and never affects pairing or PnL.
     */
    private fun fetchContractSizes(): Map<String, BigDecimal> {
        val out = HashMap<String, BigDecimal>()
        try {
            val body = client.get()
                .uri { it.path(PATH_DETAILS).build() }
                .retrieve()
                .body(String::class.java)
            val root = mapper.readTree(body ?: "{}")
            val symbols = root.path("data").path("symbols")
            if (symbols.isArray) {
                for (s in symbols) {
                    val sym = s.text(FIELD_SYMBOL)?.uppercase() ?: continue
                    val size = s.dec(FIELD_CONTRACT_SIZE) ?: continue
                    if (size.signum() > 0) out[sym] = size
                }
            }
            log.debug("BitMart contract details: {} symbols sized", out.size)
        } catch (e: Exception) {
            log.warn("BitMart contract details fetch failed; defaulting contract_size=1: {}", e.message)
        }
        return out
    }

    /**
     * KEYED GET of `/contract/private/trades` for a [start]..[end] window (epoch SECONDS). No signing —
     * only the `X-BM-KEY` header. Retries HTTP 429 / BitMart rate-limit codes with exponential backoff.
     */
    private fun call(creds: ExchangeCredentials, start: Instant, end: Instant): JsonNode {
        var attempt = 0
        while (true) {
            log.debug("BitMart request: GET {} start={} end={} attempt={}", PATH_TRADES, start, end, attempt)
            val body = try {
                client.get()
                    .uri { b ->
                        b.path(PATH_TRADES)
                        b.queryParam("start_time", start.epochSecond)
                        b.queryParam("end_time", end.epochSecond)
                        b.build()
                    }
                    .header("X-BM-KEY", creds.apiKey)
                    .retrieve()
                    .body(String::class.java)
            } catch (e: RestClientResponseException) {
                if (e.statusCode.value() == 429 && attempt < MAX_RETRIES) {
                    backoff(++attempt, "HTTP 429")
                    continue
                }
                log.warn("BitMart HTTP error: path={} status={} body={}", PATH_TRADES, e.statusCode.value(), e.responseBodyAsString.take(500))
                throw mapHttpError(e)
            }
            val root = mapper.readTree(body ?: "{}")
            val code = root.path("code").asInt(0)
            if (code == SUCCESS_CODE) return root
            val msg = root.path("message").asText(root.path("msg").asText(""))
            if (code in RATE_LIMIT_CODES && attempt < MAX_RETRIES) {
                backoff(++attempt, "code=$code msg=$msg")
                continue
            }
            log.warn("BitMart business error: path={} code={} msg={}", PATH_TRADES, code, msg)
            throw mapBusinessError(code, msg)
        }
    }

    /** Pace successive windows to stay under BitMart's ~6 req/2s limit; [more] guards the final window. */
    private fun pace(more: Boolean) {
        if (!more) return
        try {
            Thread.sleep(WINDOW_PACING_MS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Sleep with exponential backoff (500ms, 1s, 2s, …) between rate-limited retries. */
    private fun backoff(attempt: Int, reason: String) {
        val ms = RETRY_BASE_MS shl (attempt - 1)
        log.warn("BitMart rate limited ({}), retry {}/{} after {}ms", reason, attempt, MAX_RETRIES, ms)
        try {
            Thread.sleep(ms)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AppException.badRequest("SYNC_FAILED", detail = "Interrupted during BitMart backoff", cause = ie)
        }
    }

    private fun tradesArray(root: JsonNode): List<JsonNode> {
        val data = root.path("data")
        val arr = when {
            data.isArray -> data
            data.path("trades").isArray -> data.path("trades")
            else -> return emptyList()
        }
        return arr.toList()
    }

    private fun mapBusinessError(code: Int, msg: String): AppException {
        val detail = "BitMart code=$code msg=$msg"
        return when (code) {
            in AUTH_CODES -> AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = detail)
            in PERMISSION_CODES -> AppException.badRequest("DATA_SOURCE_PERMISSION_DENIED", detail = detail)
            in RATE_LIMIT_CODES -> AppException.tooManyRequests("SYNC_RATE_LIMITED")
            else -> AppException.badRequest("SYNC_FAILED", detail = detail)
        }
    }

    private fun mapHttpError(e: RestClientResponseException): AppException {
        val detail = "BitMart HTTP ${e.statusCode.value()}: ${e.responseBodyAsString.take(300)}"
        return when (e.statusCode.value()) {
            401 -> AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = detail, cause = e)
            403 -> AppException.badRequest("DATA_SOURCE_PERMISSION_DENIED", detail = detail, cause = e)
            429 -> AppException.tooManyRequests("SYNC_RATE_LIMITED")
            else -> AppException.badRequest("SYNC_FAILED", detail = detail, cause = e)
        }
    }

    private companion object {
        const val PATH_TRADES = "/contract/private/trades"
        const val PATH_DETAILS = "/contract/public/details"
        const val WINDOW_DAYS = 7L
        const val DEFAULT_BACKFILL_DAYS = 365L // safety backstop; empty-streak normally stops first
        const val MAX_WINDOWS = 320
        const val EMPTY_WINDOW_STREAK = 8 // consecutive empty 7-day windows (~2mo gap) → history exhausted
        const val PAGE_LIMIT = 200 // /contract/private/trades max rows per response (no paging param)
        const val MIN_SPLIT_MS = 60L * 60L * 1000L // don't split a window below ~1h when chasing the cap

        const val SUCCESS_CODE = 1000

        const val WINDOW_PACING_MS = 350L // ~6 req/2s ⇒ keep ≥333ms between calls
        const val MAX_RETRIES = 4
        const val RETRY_BASE_MS = 500L
        val RATE_LIMIT_CODES = setOf(40016, 429) // BitMart "too many requests" (verify against live errors)
        const val GROUP_SEP = ' '

        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_SIDE = "side"
        val FIELD_PRICE = listOf("price", "deal_avg_price")
        val FIELD_VOL = listOf("vol", "deal_size")
        val FIELD_FEE = listOf("paid_fees", "paid_fee", "fee")
        val FIELD_PNL = listOf("realised_profit", "realized_profit", "profit_amount")
        val FIELD_TIME = listOf("create_time", "createTime", "ctime", "timestamp")
        val FIELD_CONTRACT_SIZE = listOf("contract_size", "contractSize")

        val AUTH_CODES = setOf(40001, 40002, 40012) // signature / api-key errors (verify against live errors)
        val PERMISSION_CODES = setOf(40013, 40014) // permission denied (verify against live errors)
    }
}

private fun JsonNode.text(keys: List<String>): String? =
    keys.firstNotNullOfOrNull { k -> path(k).takeIf { it.isValueNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() } }

private fun JsonNode.dec(keys: List<String>): BigDecimal? =
    keys.firstNotNullOfOrNull { k ->
        path(k).takeIf { it.isValueNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull()
    }

private fun JsonNode.text(key: String): String? = text(listOf(key))
