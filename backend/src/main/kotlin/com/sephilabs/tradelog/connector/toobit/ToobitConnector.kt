// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.toobit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.position.PositionSide
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.SortedMap

/**
 * Toobit USDT-M perpetuals: closed positions sized in contracts, with `realizedPnL` already NET of the
 * two fees, so the gross is backed out. The endpoint itemises no funding, so none is recorded.
 */
@Component
class ToobitConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ClosedPositionConnector(props.connectors.toobit, mapper, props) {

    override val kind = SourceKind.TOOBIT

    /** `BTC-SWAP-USDT` → BTC/USDT: the contract-type token in the middle is not part of the pair. */
    override fun normalizeSymbol(raw: String): Symbol =
        Symbols.split(raw.uppercase().split(SYMBOL_SEP).filterNot { it in CONTRACT_TOKENS }.joinToString(SYMBOL_SEP))

    // 3000 request weight per minute, 5 per call.
    override val pacingMs = 250L
    override val rateLimitCodes = setOf("-1003", "-1015", "429")
    override val authCodes = setOf("-1002", "-1022", "-1107", "-2014", "-2015", "-2017", "-2018")
    override val permissionCodes = setOf("-1005", "-1023", "-1204")

    /** `contractMultiplier` is the base amount per contract, from Toobit's public exchange info. */
    private val contractSizes = Memo(Duration.ofHours(6)) {
        ContractSizes.from(
            publicJson(PATH_EXCHANGE_INFO),
            rowPaths = listOf("contracts"),
            symbolKeys = FIELD_SYMBOL,
            sizeKeys = FIELD_CONTRACT_MULTIPLIER,
        ).also { log.debug("Toobit exchange info: {} contracts sized", it.size) }
    }

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
            headers = mapOf("X-BB-APIKEY" to creds.apiKey),
        )
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        // A successful list response is a bare array; only errors come back as an object with a code.
        if (root.isArray) return
        val code = root.text(FIELD_CODE) ?: return
        failEnvelope(path, code, root.path("msg").asText(""))
    }

    /**
     * Id-cursor paging. Toobit documents neither the sort order nor whether the cursor is inclusive,
     * so the direction is read off each full page and rows already held are dropped.
     */
    override fun fetchRows(creds: ExchangeCredentials, since: Instant?): List<JsonNode> {
        val seen = HashSet<String>()
        return pageThrough<IdCursor>(maxPages = MAX_PAGES) { cursor ->
            val params = sortedMapOf("limit" to LIMIT.toString())
            since?.let { params["startTime"] = it.toEpochMilli().toString() }
            cursor?.let { params[it.param] = it.id }
            val page = getJson(creds, PATH_HISTORY, params).rows(ROW_ROOT)
            val fresh = page.filter { row -> row.text(FIELD_ID)?.let(seen::add) ?: true }
            Page(fresh, IdCursor.after(page).takeIf { page.size >= LIMIT })
        }
    }

    /** The next page's bound: `toId` at the smallest id when the page ran newest-first, `fromId` at the largest otherwise. */
    private class IdCursor(val param: String, val id: String) {
        companion object {
            fun after(page: List<JsonNode>): IdCursor? {
                val ids = page.mapNotNull { it.long(FIELD_ID) }
                if (ids.size < 2) return null
                return if (ids.first() > ids.last()) IdCursor("toId", ids.min().toString())
                else IdCursor("fromId", ids.max().toString())
            }
        }
    }

    /** Test seam: map a raw history-positions body with explicit contract sizes. */
    internal fun mapRows(node: JsonNode, sizes: ContractSizes): List<PositionRecord> =
        node.rows(ROW_ROOT).mapNotNull { (mapPosition(it, sizes) as? Mapped.Ok)?.value }

    override fun mapPosition(row: JsonNode): Mapped<PositionRecord> = mapPosition(row, contractSizes.get())

    private fun mapPosition(row: JsonNode, sizes: ContractSizes): Mapped<PositionRecord> {
        val symbolRaw = row.text(FIELD_SYMBOL) ?: return Mapped.Skip("no symbol")
        val externalId = row.text(FIELD_ID) ?: return Mapped.Skip("no id")
        // PARTIAL_CLOSE is a position still open; it comes back CLOSED, under the same id, once flat.
        val status = row.text(FIELD_STATUS)?.uppercase()
        if (status != null && status != STATUS_CLOSED) return Mapped.Skip("not closed (status=$status)")
        val openedAt = row.instant(FIELD_OPEN_TIME) ?: return Mapped.Skip("no open time")
        val closedAt = row.instant(FIELD_CLOSE_TIME) ?: openedAt
        val side = when (row.text(FIELD_SIDE)?.uppercase()) {
            SIDE_LONG -> PositionSide.LONG
            SIDE_SHORT -> PositionSide.SHORT
            else -> return Mapped.Skip("no side")
        }

        val fees = (row.dec(FIELD_OPEN_FEE) ?: BigDecimal.ZERO).abs()
            .add((row.dec(FIELD_CLOSE_FEE) ?: BigDecimal.ZERO).abs())
        // `realizedPnL` is the figure Toobit shows, net of both fees; adding them back makes net derive to it.
        val gross = row.dec(FIELD_NET_PNL)?.add(fees) ?: row.dec(FIELD_GROSS_PNL) ?: BigDecimal.ZERO
        val contracts = (row.dec(FIELD_CLOSED_QTY) ?: row.dec(FIELD_POSITION) ?: BigDecimal.ZERO).abs()
        return Mapped.Ok(
            PositionRecord(
                externalId = externalId,
                symbol = normalizeSymbol(symbolRaw),
                side = side,
                openedAt = openedAt,
                closedAt = closedAt,
                qty = contracts.multiply(sizes.of(symbolRaw)),
                entryPrice = row.dec(FIELD_OPEN_PX) ?: BigDecimal.ZERO,
                exitPrice = row.dec(FIELD_CLOSE_PX) ?: BigDecimal.ZERO,
                realizedPnl = gross,
                fees = fees,
                fills = emptyList(),
                raw = row.toString(),
            ),
        )
    }

    private companion object {
        const val PATH_HISTORY = "/api/v1/futures/historyPositions"
        const val PATH_EXCHANGE_INFO = "/api/v1/exchangeInfo"
        const val LIMIT = 1000 // documented maximum
        const val MAX_PAGES = 100
        const val STATUS_CLOSED = "CLOSED"
        const val SIDE_LONG = "LONG"
        const val SIDE_SHORT = "SHORT"
        const val SYMBOL_SEP = "-"

        /** Contract-type tokens Toobit puts between base and quote. */
        val CONTRACT_TOKENS = setOf("SWAP", "PERP")

        /** The history list is the document root. */
        val ROW_ROOT = listOf("")

        val FIELD_CODE = listOf("code")
        val FIELD_ID = listOf("id")
        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_SIDE = listOf("side")
        val FIELD_STATUS = listOf("status")
        val FIELD_OPEN_TIME = listOf("openTime")
        val FIELD_CLOSE_TIME = listOf("closeTime")
        val FIELD_OPEN_PX = listOf("openAvgPrice")
        val FIELD_CLOSE_PX = listOf("closeAvgPrice")
        val FIELD_CLOSED_QTY = listOf("closeTotalQty")
        val FIELD_POSITION = listOf("position", "maxPosition")
        val FIELD_NET_PNL = listOf("realizedPnL", "realizedPnl")
        val FIELD_GROSS_PNL = listOf("realizedPnlWithoutFee")
        val FIELD_OPEN_FEE = listOf("openFee")
        val FIELD_CLOSE_FEE = listOf("closeFee")
        val FIELD_CONTRACT_MULTIPLIER = listOf("contractMultiplier")
    }
}
