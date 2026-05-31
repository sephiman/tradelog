// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.bitunix

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.ApiConnector
import com.sephilabs.tradelog.connector.ExchangeCredentials
import com.sephilabs.tradelog.connector.ExchangeSign
import com.sephilabs.tradelog.connector.PositionRecord
import com.sephilabs.tradelog.connector.SyncBatch
import com.sephilabs.tradelog.connector.SyncCursor
import com.sephilabs.tradelog.connector.Symbol
import com.sephilabs.tradelog.connector.Symbols
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.position.PositionSide
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Bitunix USDT-M futures connector. Bitunix returns already-closed positions with realized PnL,
 * so each history row maps 1:1 to a canonical position; no reconstruction is needed.
 *
 * Auth (Bitunix double-SHA256 scheme): digest = sha256(nonce + timestamp + apiKey + sortedQuery + body);
 * sign = sha256(digest + apiSecret). Sent via api-key / sign / nonce / timestamp headers.
 *
 * VERIFY: the exact response field names below are coded against Bitunix's documented
 * get_history_positions shape. They are looked up defensively (multiple candidate keys); confirm
 * against a real payload and adjust [FIELD_*] / [mapPosition] if the live API differs.
 */
@Component
class BitunixConnector(
    private val props: AppProperties,
    private val mapper: ObjectMapper,
) : ApiConnector {

    private val log = LoggerFactory.getLogger(BitunixConnector::class.java)
    private val client: RestClient = RestClient.builder().baseUrl(props.connectors.bitunix.baseUrl).build()

    override val kind = SourceKind.BITUNIX

    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw)

    override fun fetchClosedPositions(
        credentials: ExchangeCredentials,
        cursor: SyncCursor,
        backfillFrom: Instant?,
    ): SyncBatch {
        val since = cursor.lastClosedAt ?: backfillFrom
        val records = mutableListOf<PositionRecord>()
        var maxClosed: Instant? = cursor.lastClosedAt
        val skips = LinkedHashMap<String, Int>() // reason -> count, aggregated across pages
        var firstSkipSample: String? = null
        var skip = 0
        var page = 0
        while (page < MAX_PAGES) {
            val params = sortedMapOf("limit" to LIMIT.toString(), "skip" to skip.toString())
            since?.let { params["startTime"] = it.toEpochMilli().toString() }
            val node = call(credentials, PATH_HISTORY, params)
            val list = positionsArray(node)
            if (list.isEmpty()) break
            for (n in list) {
                when (val r = mapPosition(n)) {
                    is PositionResult.Skip -> {
                        skips.merge(r.reason, 1, Int::plus)
                        if (firstSkipSample == null) firstSkipSample = n.toString().take(400)
                    }
                    is PositionResult.Ok -> {
                        val rec = r.record
                        if (since == null || rec.closedAt.isAfter(since)) {
                            records += rec
                            if (maxClosed == null || rec.closedAt.isAfter(maxClosed)) maxClosed = rec.closedAt
                        }
                    }
                }
            }
            if (list.size < LIMIT) break
            skip += LIMIT
            page++
        }
        // Surface dropped positions loudly rather than swallowing them silently (a renamed field
        // would otherwise quietly omit trades from the journal).
        if (skips.isNotEmpty()) {
            log.warn("Bitunix: skipped {} position(s) {} — first offender: {}", skips.values.sum(), skips, firstSkipSample)
        }
        log.info("Bitunix fetch: {} closed positions (since={})", records.size, since)
        return SyncBatch(records, SyncCursor(lastClosedAt = maxClosed, lastExternalId = records.lastOrNull()?.externalId))
    }

    private fun call(creds: ExchangeCredentials, path: String, params: Map<String, String>): JsonNode {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val timestamp = Instant.now().toEpochMilli().toString()
        // Sorted concatenation key1value1key2value2... (no separators), body empty for GET.
        val queryConcat = params.entries.joinToString("") { it.key + it.value }
        val digest = ExchangeSign.sha256Hex(nonce + timestamp + creds.apiKey + queryConcat)
        val sign = ExchangeSign.sha256Hex(digest + creds.apiSecret)
        log.debug("Bitunix request: GET {} params={}", path, params) // never log secret/sign
        val body = try {
            client.get()
                .uri { b -> b.path(path).also { params.forEach { (k, v) -> b.queryParam(k, v) } }.build() }
                .header("api-key", creds.apiKey)
                .header("sign", sign)
                .header("nonce", nonce)
                .header("timestamp", timestamp)
                .header("language", "en-US")
                .retrieve()
                .body(String::class.java)
        } catch (e: RestClientResponseException) {
            log.warn("Bitunix HTTP error: path={} status={} body={}", path, e.statusCode.value(), e.responseBodyAsString.take(500))
            throw mapHttpError(e)
        }
        val root = mapper.readTree(body ?: "{}")
        val code = root.path("code").asText("0")
        if (code != "0" && code.isNotBlank()) {
            val msg = root.path("msg").asText("")
            log.warn("Bitunix business error: path={} code={} msg={}", path, code, msg)
            throw mapBusinessError(code, msg)
        }
        return root
    }

    private fun mapPosition(n: JsonNode): PositionResult {
        val externalId = n.text(FIELD_ID) ?: return PositionResult.Skip("no id")
        val symbolRaw = n.text(FIELD_SYMBOL) ?: return PositionResult.Skip("no symbol")
        val openMs = n.long(FIELD_OPEN_TIME) ?: return PositionResult.Skip("no open time")
        val closeMs = n.long(FIELD_CLOSE_TIME) ?: openMs

        // Bitunix's realizedPNL is already NET (fees + funding deducted). The canonical model wants
        // realizedPnl to be GROSS so net = realizedPnl − fees − funding holds uniformly across sources
        // (BingX/Quantfury/CSV all store gross). So store fees as a non-negative cost and back the
        // gross out: gross = net + fees + funding. (If the funding sign ever looks inverted vs the
        // Bitunix app, negate it here — the derived net is exact either way.)
        val netPnl = n.dec(FIELD_PNL) ?: BigDecimal.ZERO
        val fees = (n.dec(FIELD_FEE) ?: BigDecimal.ZERO).abs()
        val funding = n.dec(FIELD_FUNDING) ?: BigDecimal.ZERO
        val grossPnl = netPnl.add(fees).add(funding)
        return PositionResult.Ok(
            PositionRecord(
                externalId = externalId,
                symbol = normalizeSymbol(symbolRaw),
                side = parseSide(n.text(FIELD_SIDE)),
                openedAt = Instant.ofEpochMilli(openMs),
                closedAt = Instant.ofEpochMilli(closeMs),
                qty = n.dec(FIELD_QTY) ?: BigDecimal.ZERO,
                entryPrice = n.dec(FIELD_ENTRY) ?: BigDecimal.ZERO,
                exitPrice = n.dec(FIELD_EXIT) ?: BigDecimal.ZERO,
                realizedPnl = grossPnl,
                fees = fees,
                funding = funding,
                fills = emptyList(), // Bitunix history endpoint returns aggregate positions, not legs.
                raw = n.toString(),
            )
        )
    }

    private sealed interface PositionResult {
        data class Ok(val record: PositionRecord) : PositionResult
        data class Skip(val reason: String) : PositionResult
    }

    private fun positionsArray(root: JsonNode): List<JsonNode> {
        val data = root.path("data")
        val arr = when {
            data.isArray -> data
            data.path("positionList").isArray -> data.path("positionList")
            data.path("list").isArray -> data.path("list")
            else -> return emptyList()
        }
        return arr.toList()
    }

    private fun parseSide(raw: String?): PositionSide = when (raw?.uppercase()) {
        "SELL", "SHORT", "2" -> PositionSide.SHORT
        else -> PositionSide.LONG
    }

    private fun mapBusinessError(code: String, msg: String): AppException {
        val detail = "Bitunix code=$code msg=$msg"
        return when (code) {
            in AUTH_CODES -> AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = detail)
            in PERMISSION_CODES -> AppException.badRequest("DATA_SOURCE_PERMISSION_DENIED", detail = detail)
            else -> AppException.badRequest("SYNC_FAILED", detail = detail)
        }
    }

    private fun mapHttpError(e: RestClientResponseException): AppException {
        val detail = "Bitunix HTTP ${e.statusCode.value()}: ${e.responseBodyAsString.take(300)}"
        return when (e.statusCode.value()) {
            401 -> AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = detail, cause = e)
            403 -> AppException.badRequest("DATA_SOURCE_PERMISSION_DENIED", detail = detail, cause = e)
            else -> AppException.badRequest("SYNC_FAILED", detail = detail, cause = e)
        }
    }

    private companion object {
        const val PATH_HISTORY = "/api/v1/futures/position/get_history_positions"
        const val LIMIT = 100
        const val MAX_PAGES = 50

        // Defensive multi-key field candidates — adjust to the verified Bitunix schema.
        val FIELD_ID = listOf("positionId", "id")
        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_SIDE = listOf("side", "positionSide")
        val FIELD_OPEN_TIME = listOf("ctime", "createTime", "openTime")
        val FIELD_CLOSE_TIME = listOf("mtime", "updateTime", "closeTime")
        val FIELD_QTY = listOf("qty", "maxQty", "size", "volume")
        val FIELD_ENTRY = listOf("entryPrice", "avgOpenPrice", "openPrice")
        val FIELD_EXIT = listOf("closePrice", "avgClosePrice", "exitPrice")
        val FIELD_PNL = listOf("realizedPNL", "realizedPnl", "pnl", "profit")
        val FIELD_FEE = listOf("fee", "fees", "tradeFee")
        val FIELD_FUNDING = listOf("funding", "fundingFee")

        val AUTH_CODES = setOf("10003", "10004", "10007") // invalid key / sign / nonce (verify)
        val PERMISSION_CODES = setOf("10005", "10006")     // permission denied (verify)
    }
}

private fun JsonNode.text(keys: List<String>): String? =
    keys.firstNotNullOfOrNull { k -> path(k).takeIf { it.isValueNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() } }

private fun JsonNode.long(keys: List<String>): Long? =
    keys.firstNotNullOfOrNull { k -> path(k).takeIf { it.isValueNode && !it.isNull }?.asLong() }

private fun JsonNode.dec(keys: List<String>): BigDecimal? =
    keys.firstNotNullOfOrNull { k ->
        path(k).takeIf { it.isValueNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull()
    }
