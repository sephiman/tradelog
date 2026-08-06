// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.okx

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
import java.time.format.DateTimeFormatter
import java.util.SortedMap

/** OKX perpetual swaps: closed positions with PnL, fees and funding all reported separately. */
@Component
class OkxConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ClosedPositionConnector(props.connectors.okx, mapper, props) {

    override val kind = SourceKind.OKX

    /** `BTC-USDT-SWAP` → BTC/USDT: drop the instrument-type suffix, then split on the separator. */
    override fun normalizeSymbol(raw: String): Symbol =
        Symbols.split(raw.uppercase().removeSuffix(SWAP_SUFFIX))

    // 10 requests / 2s per user+instrument-type; the pacing below keeps a paged walk clear of it.
    override val pacingMs = 250L
    override val rateLimitCodes = setOf("50011", "50061") // too many requests
    override val authCodes = setOf("50100", "50101", "50102", "50103", "50104", "50105", "50111", "50113")
    override val permissionCodes = setOf("50030", "50114") // key lacks the required permission

    /** `ctVal` is the base amount per contract; instrument listings change with new markets, not trades. */
    private val contractSizes = Memo(Duration.ofHours(6)) {
        ContractSizes.from(
            publicJson(PATH_INSTRUMENTS, mapOf("instType" to INST_TYPE)),
            rowPaths = listOf("data"),
            symbolKeys = FIELD_INST_ID,
            sizeKeys = FIELD_CT_VAL,
        ).also { log.debug("OKX instruments: {} swaps sized", it.size) }
    }

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        // The signature covers the path AND the query string, so it must be built from the same sorted
        // map the base class then sends — any divergence in order or encoding is a 401.
        val queryString = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        val requestPath = if (queryString.isEmpty()) path else "$path?$queryString"
        val timestamp = ISO_MILLIS.format(Instant.now())
        val signature = ExchangeSign.hmacSha256Base64(
            creds.apiSecret,
            timestamp + method.uppercase() + requestPath,
        )
        return Auth(
            headers = mapOf(
                "OK-ACCESS-KEY" to creds.apiKey,
                "OK-ACCESS-SIGN" to signature,
                "OK-ACCESS-TIMESTAMP" to timestamp,
                "OK-ACCESS-PASSPHRASE" to requirePassphrase(creds),
                "Content-Type" to "application/json",
            ),
        )
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        val code = root.path("code").asText("0")
        if (code == "0" || code.isBlank()) return
        val msg = root.path("msg").asText("")
        // OKX reports per-row failures in `data[].sMsg`; for a read that only happens on a bad request.
        failEnvelope(path, code, msg)
    }

    /** Pages backward via the `after` cursor, which OKX keys on `posId`. */
    override fun fetchRows(creds: ExchangeCredentials, since: Instant?): List<JsonNode> =
        pageThrough<String>(maxPages = MAX_PAGES) { after ->
            val params = sortedMapOf(
                "instType" to INST_TYPE,
                "limit" to LIMIT.toString(),
            )
            after?.let { params["after"] = it }
            since?.let { params["begin"] = it.toEpochMilli().toString() }
            val rows = getJson(creds, PATH_HISTORY, params).rows(ROW_PATHS)
            // Only a full page can have more behind it; `posId` is the documented cursor value.
            Page(rows, rows.lastOrNull()?.text(FIELD_POS_ID)?.takeIf { rows.size >= LIMIT })
        }

    /** Test seam: map a raw positions-history body with explicit contract sizes. */
    internal fun mapRows(node: JsonNode, sizes: ContractSizes): List<PositionRecord> =
        node.rows(ROW_PATHS).mapNotNull { (mapPosition(it, sizes) as? Mapped.Ok)?.value }

    override fun mapPosition(row: JsonNode): Mapped<PositionRecord> = mapPosition(row, contractSizes.get())

    private fun mapPosition(row: JsonNode, sizes: ContractSizes): Mapped<PositionRecord> {
        val instId = row.text(FIELD_INST_ID) ?: return Mapped.Skip("no instId")
        val externalId = row.text(FIELD_POS_ID) ?: return Mapped.Skip("no posId")
        val openedAt = row.instant(FIELD_OPEN_TIME) ?: return Mapped.Skip("no open time")
        val closedAt = row.instant(FIELD_CLOSE_TIME) ?: openedAt
        val symbol = normalizeSymbol(instId)
        if (symbol.quote !in LINEAR_QUOTES) return Mapped.Skip("not a linear swap (${symbol.quote})")

        // `pnl` is gross; `fee`/`fundingFee`/`liqPenalty` are NEGATIVE when charged, so both are
        // negated into costs. Net then works out to OKX's own `realizedPnl`.
        val gross = row.dec(FIELD_PNL) ?: BigDecimal.ZERO
        val fee = row.dec(FIELD_FEE) ?: BigDecimal.ZERO
        val penalty = row.dec(FIELD_LIQ_PENALTY) ?: BigDecimal.ZERO
        val funding = row.dec(FIELD_FUNDING) ?: BigDecimal.ZERO
        val closedContracts = row.dec(FIELD_CLOSE_POS) ?: row.dec(FIELD_OPEN_POS) ?: BigDecimal.ZERO
        return Mapped.Ok(
            PositionRecord(
                externalId = externalId,
                symbol = symbol,
                side = if (row.text(FIELD_DIRECTION)?.lowercase() == "short") PositionSide.SHORT else PositionSide.LONG,
                openedAt = openedAt,
                closedAt = closedAt,
                qty = closedContracts.multiply(sizes.of(instId)),
                entryPrice = row.dec(FIELD_OPEN_PX) ?: BigDecimal.ZERO,
                exitPrice = row.dec(FIELD_CLOSE_PX) ?: BigDecimal.ZERO,
                realizedPnl = gross,
                fees = fee.abs().add(penalty.abs()),
                funding = funding.negate(),
                fills = emptyList(), // the history endpoint returns whole positions, not legs
                raw = row.toString(),
            ),
        )
    }

    private companion object {
        const val PATH_HISTORY = "/api/v5/account/positions-history"
        const val PATH_INSTRUMENTS = "/api/v5/public/instruments"
        const val INST_TYPE = "SWAP"
        const val SWAP_SUFFIX = "-SWAP"
        const val LIMIT = 100
        const val MAX_PAGES = 60 // 6,000 positions; 90 days of history is far short of that

        /** OKX wants milliseconds and a `Z` offset, e.g. `2026-08-06T09:08:57.715Z`. */
        val ISO_MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)

        /** Quote currencies whose `sz` is a base amount, so the imported quantity is comparable. */
        val LINEAR_QUOTES = setOf("USDT", "USDC")

        val ROW_PATHS = listOf("data")
        val FIELD_INST_ID = listOf("instId")
        val FIELD_POS_ID = listOf("posId")
        val FIELD_DIRECTION = listOf("direction", "posSide")
        val FIELD_OPEN_TIME = listOf("cTime")
        val FIELD_CLOSE_TIME = listOf("uTime")
        val FIELD_OPEN_PX = listOf("openAvgPx")
        val FIELD_CLOSE_PX = listOf("closeAvgPx")
        val FIELD_CLOSE_POS = listOf("closeTotalPos")
        val FIELD_OPEN_POS = listOf("openMaxPos")
        val FIELD_PNL = listOf("pnl")
        val FIELD_FEE = listOf("fee")
        val FIELD_FUNDING = listOf("fundingFee")
        val FIELD_LIQ_PENALTY = listOf("liqPenalty")
        val FIELD_CT_VAL = listOf("ctVal")
    }
}
