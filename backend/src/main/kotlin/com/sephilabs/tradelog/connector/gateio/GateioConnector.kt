// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.gateio

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
 * Gate.io USDT perpetuals. `position_close` reports one row per flat-to-flat position with the PnL
 * already split the way this model stores it: `pnl_pnl` trading result, `pnl_fee` fees, `pnl_fund` funding.
 */
@Component
class GateioConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ClosedPositionConnector(props.connectors.gateio, mapper, props) {

    override val kind = SourceKind.GATEIO_FUTURES

    /** `BTC_USDT` → BTC/USDT, which the shared splitter already handles via the underscore. */
    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw)

    override val pacingMs = 200L
    override val rateLimitCodes = setOf("TOO_MANY_REQUESTS", "REQUEST_RATE_LIMITED", "429")
    override val authCodes = setOf("INVALID_KEY", "INVALID_SIGNATURE", "AUTHENTICATION_FAILED", "INVALID_CREDENTIALS")
    override val permissionCodes = setOf("FORBIDDEN", "READ_ONLY", "MISSING_REQUIRED_HEADER")

    /** `quanto_multiplier` is the base amount per contract; Gate publishes it without authentication. */
    private val contractSizes = Memo(Duration.ofHours(6)) {
        ContractSizes.from(
            publicJson("$PREFIX/futures/$SETTLE/contracts"),
            rowPaths = listOf(""),
            symbolKeys = FIELD_CONTRACT,
            sizeKeys = FIELD_MULTIPLIER,
        ).also { log.debug("Gate.io contracts: {} sized", it.size) }
    }

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        val queryString = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        val timestamp = Instant.now().epochSecond.toString()
        val payload = listOf(
            method.uppercase(),
            path,
            queryString,
            ExchangeSign.sha512Hex(""), // no body on a GET, but the digest is still part of the message
            timestamp,
        ).joinToString("\n")
        return Auth(
            headers = mapOf(
                "KEY" to creds.apiKey,
                "SIGN" to ExchangeSign.hmacSha512Hex(creds.apiSecret, payload),
                "Timestamp" to timestamp,
                "Accept" to "application/json",
            ),
        )
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        // A successful list response is a bare JSON array; only an error is an object with a label.
        if (root.isArray) return
        val label = root.text(FIELD_ERROR_LABEL) ?: return
        failEnvelope(path, label, root.path("message").asText(""))
    }

    /** Offset paging: Gate has no cursor here, and a short page is the last one. */
    override fun fetchRows(creds: ExchangeCredentials, since: Instant?): List<JsonNode> =
        pageThrough<Int>(maxPages = MAX_PAGES) { offset ->
            val params = sortedMapOf(
                "limit" to LIMIT.toString(),
                "offset" to (offset ?: 0).toString(),
            )
            since?.let { params["from"] = it.epochSecond.toString() }
            val rows = getJson(creds, pathHistory, params).rows(ROW_PATHS)
            Page(rows, if (rows.size < LIMIT) null else (offset ?: 0) + LIMIT)
        }

    /** Test seam: map a raw `position_close` body with explicit contract sizes. */
    internal fun mapRows(node: JsonNode, sizes: ContractSizes): List<PositionRecord> =
        node.rows(ROW_PATHS).mapNotNull { (mapPosition(it, sizes) as? Mapped.Ok)?.value }

    override fun mapPosition(row: JsonNode): Mapped<PositionRecord> = mapPosition(row, contractSizes.get())

    private fun mapPosition(row: JsonNode, sizes: ContractSizes): Mapped<PositionRecord> {
        val contract = row.text(FIELD_CONTRACT) ?: return Mapped.Skip("no contract")
        // `time` is fractional epoch seconds, which the shared timestamp reader keeps to the millisecond.
        val closedAt = row.instant(FIELD_CLOSE_TIME) ?: return Mapped.Skip("no close time")
        val openedAt = row.instant(FIELD_OPEN_TIME) ?: closedAt
        val side = if (row.text(FIELD_SIDE)?.lowercase() == "short") PositionSide.SHORT else PositionSide.LONG

        val longPrice = row.dec(FIELD_LONG_PRICE) ?: BigDecimal.ZERO
        val shortPrice = row.dec(FIELD_SHORT_PRICE) ?: BigDecimal.ZERO
        // For a long, long_price opened and short_price closed it; for a short it is the other way round.
        val entry = if (side == PositionSide.LONG) longPrice else shortPrice
        val exit = if (side == PositionSide.LONG) shortPrice else longPrice

        // Gate reports the components directly. `pnl_fee` and `pnl_fund` are negative when charged, so
        // fees become a non-negative cost and funding a signed one; net then derives back to `pnl`.
        val gross = row.dec(FIELD_PNL_TRADE) ?: BigDecimal.ZERO
        val fee = row.dec(FIELD_PNL_FEE) ?: BigDecimal.ZERO
        val funding = row.dec(FIELD_PNL_FUND) ?: BigDecimal.ZERO
        val contracts = (row.dec(FIELD_ACCUM_SIZE) ?: row.dec(FIELD_MAX_SIZE) ?: BigDecimal.ZERO).abs()
        return Mapped.Ok(
            PositionRecord(
                externalId = externalId(contract, openedAt, closedAt, side),
                symbol = normalizeSymbol(contract),
                side = side,
                openedAt = openedAt,
                closedAt = closedAt,
                qty = contracts.multiply(sizes.of(contract)),
                entryPrice = entry,
                exitPrice = exit,
                realizedPnl = gross,
                fees = fee.abs(),
                funding = funding.negate(),
                fills = emptyList(),
                raw = row.toString(),
            ),
        )
    }

    /** Gate gives no position id; these four inputs are lifecycle properties, so re-syncs match. */
    private fun externalId(contract: String, openedAt: Instant, closedAt: Instant, side: PositionSide): String =
        "$contract-${openedAt.toEpochMilli()}-${closedAt.toEpochMilli()}-${side.name.first()}"

    private val pathHistory: String get() = "$PREFIX/futures/$SETTLE/position_close"

    private companion object {
        const val PREFIX = "/api/v4"
        const val SETTLE = "usdt"
        const val LIMIT = 100
        const val MAX_PAGES = 100

        /** A successful list response is the array itself, so the row path is the document root. */
        val ROW_PATHS = listOf("")

        val FIELD_CONTRACT = listOf("contract", "name")
        val FIELD_SIDE = listOf("side")
        val FIELD_CLOSE_TIME = listOf("time")
        val FIELD_OPEN_TIME = listOf("first_open_time")
        val FIELD_LONG_PRICE = listOf("long_price")
        val FIELD_SHORT_PRICE = listOf("short_price")
        val FIELD_ACCUM_SIZE = listOf("accum_size")
        val FIELD_MAX_SIZE = listOf("max_size")
        val FIELD_PNL_TRADE = listOf("pnl_pnl")
        val FIELD_PNL_FEE = listOf("pnl_fee")
        val FIELD_PNL_FUND = listOf("pnl_fund")
        val FIELD_MULTIPLIER = listOf("quanto_multiplier")
        val FIELD_ERROR_LABEL = listOf("label")
    }
}
