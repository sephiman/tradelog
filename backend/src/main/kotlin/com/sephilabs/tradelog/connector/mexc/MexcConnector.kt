// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.mexc

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
 * MEXC USDT-M futures: closed positions, with `closeProfitLoss` already the gross figure wanted here.
 * MEXC restricts futures API access, so a new key may be refused — a permissions error is that limit.
 */
@Component
class MexcConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ClosedPositionConnector(props.connectors.mexc, mapper, props) {

    override val kind = SourceKind.MEXC_FUTURES

    /** `BTC_USDT` → BTC/USDT via the shared splitter's underscore handling. */
    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw)

    // 20 requests / 2s on this endpoint.
    override val pacingMs = 250L
    override val rateLimitCodes = setOf("510", "429", "8821")
    override val authCodes = setOf("600", "601", "602", "700", "701", "10072")
    override val permissionCodes = setOf("603", "604", "1002", "2011")

    /** `contractSize` is the base amount per contract, from MEXC's public contract detail listing. */
    private val contractSizes = Memo(Duration.ofHours(6)) {
        ContractSizes.from(
            publicJson(PATH_DETAIL),
            rowPaths = listOf("data"),
            symbolKeys = FIELD_SYMBOL,
            sizeKeys = FIELD_CONTRACT_SIZE,
        ).also { log.debug("MEXC contract detail: {} symbols sized", it.size) }
    }

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        val timestamp = Instant.now().toEpochMilli().toString()
        val queryString = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        return Auth(
            headers = mapOf(
                "ApiKey" to creds.apiKey,
                "Request-Time" to timestamp,
                "Signature" to ExchangeSign.hmacSha256Hex(creds.apiSecret, creds.apiKey + timestamp + queryString),
                "Content-Type" to "application/json",
            ),
        )
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        // MEXC signals success with a boolean rather than a code, and reports the code only on failure.
        if (root.path("success").asBoolean(true)) return
        failEnvelope(path, root.text(FIELD_CODE) ?: "unknown", root.path("message").asText(""))
    }

    /** Page-number paging; MEXC reports the total page count, and a short page also ends the walk. */
    override fun fetchRows(creds: ExchangeCredentials, since: Instant?): List<JsonNode> =
        pageThrough<Int>(maxPages = MAX_PAGES) { page ->
            val current = page ?: 1
            val params = sortedMapOf(
                "page_num" to current.toString(),
                "page_size" to PAGE_SIZE.toString(),
            )
            since?.let { params["start_time"] = it.toEpochMilli().toString() }
            val root = getJson(creds, PATH_HISTORY, params)
            val rows = root.rows(ROW_PATHS)
            val totalPages = root.path("data").int(FIELD_TOTAL_PAGE) ?: Int.MAX_VALUE
            Page(rows, (current + 1).takeIf { rows.size >= PAGE_SIZE && current < totalPages })
        }

    /** Test seam: map a raw history-positions body with explicit contract sizes. */
    internal fun mapRows(node: JsonNode, sizes: ContractSizes): List<PositionRecord> =
        node.rows(ROW_PATHS).mapNotNull { (mapPosition(it, sizes) as? Mapped.Ok)?.value }

    override fun mapPosition(row: JsonNode): Mapped<PositionRecord> = mapPosition(row, contractSizes.get())

    private fun mapPosition(row: JsonNode, sizes: ContractSizes): Mapped<PositionRecord> {
        val symbolRaw = row.text(FIELD_SYMBOL) ?: return Mapped.Skip("no symbol")
        val externalId = row.text(FIELD_POSITION_ID) ?: return Mapped.Skip("no positionId")
        val openedAt = row.instant(FIELD_OPEN_TIME) ?: return Mapped.Skip("no open time")
        val closedAt = row.instant(FIELD_CLOSE_TIME) ?: openedAt
        // state: 1 holding, 2 system-held, 3 closed. Only a closed position is a canonical position.
        val state = row.int(FIELD_STATE)
        if (state != null && state != STATE_CLOSED) return Mapped.Skip("not closed (state=$state)")

        // `holdFee` is funding, documented positive = received, so it is negated into a cost.
        // Net then derives back to MEXC's own `realised`.
        val gross = row.dec(FIELD_CLOSE_PNL) ?: row.dec(FIELD_REALISED) ?: BigDecimal.ZERO
        val fees = (row.dec(FIELD_TOTAL_FEE) ?: row.dec(FIELD_FEE) ?: BigDecimal.ZERO).abs()
        val funding = row.dec(FIELD_HOLD_FEE) ?: BigDecimal.ZERO
        val contracts = (row.dec(FIELD_CLOSE_VOL) ?: row.dec(FIELD_HOLD_VOL) ?: BigDecimal.ZERO).abs()
        return Mapped.Ok(
            PositionRecord(
                externalId = externalId,
                symbol = normalizeSymbol(symbolRaw),
                // positionType: 1 long, 2 short.
                side = if (row.int(FIELD_POSITION_TYPE) == POSITION_SHORT) PositionSide.SHORT else PositionSide.LONG,
                openedAt = openedAt,
                closedAt = closedAt,
                qty = contracts.multiply(sizes.of(symbolRaw)),
                entryPrice = row.dec(FIELD_OPEN_PX) ?: BigDecimal.ZERO,
                exitPrice = row.dec(FIELD_CLOSE_PX) ?: BigDecimal.ZERO,
                realizedPnl = gross,
                fees = fees,
                funding = funding.negate(),
                fills = emptyList(),
                raw = row.toString(),
            ),
        )
    }

    private companion object {
        const val PATH_HISTORY = "/api/v1/private/position/list/history_positions"
        const val PATH_DETAIL = "/api/v1/contract/detail"
        const val PAGE_SIZE = 100 // documented maximum
        const val MAX_PAGES = 100
        const val STATE_CLOSED = 3
        const val POSITION_SHORT = 2

        val ROW_PATHS = listOf("data.resultList", "data.list", "data")
        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_POSITION_ID = listOf("positionId")
        val FIELD_POSITION_TYPE = listOf("positionType")
        val FIELD_STATE = listOf("state")
        val FIELD_OPEN_TIME = listOf("createTime")
        val FIELD_CLOSE_TIME = listOf("updateTime")
        val FIELD_OPEN_PX = listOf("openAvgPrice", "holdAvgPrice")
        val FIELD_CLOSE_PX = listOf("closeAvgPrice")
        val FIELD_CLOSE_VOL = listOf("closeVol")
        val FIELD_HOLD_VOL = listOf("holdVol")
        val FIELD_CLOSE_PNL = listOf("closeProfitLoss")
        val FIELD_REALISED = listOf("realised")
        val FIELD_FEE = listOf("fee")
        val FIELD_TOTAL_FEE = listOf("totalFee")
        val FIELD_HOLD_FEE = listOf("holdFee")
        val FIELD_CONTRACT_SIZE = listOf("contractSize")
        val FIELD_TOTAL_PAGE = listOf("totalPage")
        val FIELD_CODE = listOf("code")
    }
}
