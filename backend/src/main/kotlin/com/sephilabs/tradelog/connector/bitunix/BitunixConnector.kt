// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.bitunix

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.position.PositionSide
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.SortedMap
import java.util.UUID

/** Bitunix USDT-M futures: closed positions, 1:1, reaching back to when the account was opened. */
@Component
class BitunixConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ClosedPositionConnector(props.connectors.bitunix, mapper, props) {

    override val kind = SourceKind.BITUNIX

    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw)

    override val authCodes = setOf("10003", "10004", "10007") // invalid key / sign / nonce (verify)
    override val permissionCodes = setOf("10005", "10006") // permission denied (verify)

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val timestamp = Instant.now().toEpochMilli().toString()
        // Sorted concatenation key1value1key2value2… with no separators; body empty for GET.
        val concat = query.entries.joinToString("") { it.key + it.value }
        val digest = ExchangeSign.sha256Hex(nonce + timestamp + creds.apiKey + concat)
        return Auth(
            headers = mapOf(
                "api-key" to creds.apiKey,
                "sign" to ExchangeSign.sha256Hex(digest + creds.apiSecret),
                "nonce" to nonce,
                "timestamp" to timestamp,
                "language" to "en-US",
            ),
        )
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        val code = root.path("code").asText("0")
        if (code == "0" || code.isBlank()) return
        failEnvelope(path, code, root.path("msg").asText(""))
    }

    /** Pages by row offset. [since] filters on OPEN time, hence the base class's overlap. */
    override fun fetchRows(creds: ExchangeCredentials, since: Instant?): List<JsonNode> =
        pageThrough<Int>(maxPages = MAX_PAGES) { skip ->
            val params = sortedMapOf("limit" to LIMIT.toString(), "skip" to (skip ?: 0).toString())
            since?.let { params["startTime"] = it.toEpochMilli().toString() }
            val rows = getJson(creds, PATH_HISTORY, params).rows(ROW_PATHS)
            // A short page is the last page; only a full one can have more behind it.
            Page(rows, if (rows.size < LIMIT) null else (skip ?: 0) + LIMIT)
        }

    /** Test seam: map a raw `get_history_positions` body, dropping unmappable rows. */
    internal fun mapPositions(node: JsonNode): List<PositionRecord> =
        node.rows(ROW_PATHS).mapNotNull { (mapPosition(it) as? Mapped.Ok)?.value }

    override fun mapPosition(row: JsonNode): Mapped<PositionRecord> {
        val externalId = row.text(FIELD_ID) ?: return Mapped.Skip("no id")
        val symbolRaw = row.text(FIELD_SYMBOL) ?: return Mapped.Skip("no symbol")
        val openedAt = row.instant(FIELD_OPEN_TIME) ?: return Mapped.Skip("no open time")
        val closedAt = row.instant(FIELD_CLOSE_TIME) ?: openedAt

        // Bitunix's realizedPNL is already NET, so the gross is backed out: gross = net + fees + funding.
        // (If funding ever looks inverted against the app, negate it — the derived net is exact either way.)
        val netPnl = row.dec(FIELD_PNL) ?: BigDecimal.ZERO
        val fees = (row.dec(FIELD_FEE) ?: BigDecimal.ZERO).abs()
        val funding = row.dec(FIELD_FUNDING) ?: BigDecimal.ZERO
        return Mapped.Ok(
            PositionRecord(
                externalId = externalId,
                symbol = normalizeSymbol(symbolRaw),
                side = parseSide(row.text(FIELD_SIDE)),
                openedAt = openedAt,
                closedAt = closedAt,
                qty = row.dec(FIELD_QTY) ?: BigDecimal.ZERO,
                entryPrice = row.dec(FIELD_ENTRY) ?: BigDecimal.ZERO,
                exitPrice = row.dec(FIELD_EXIT) ?: BigDecimal.ZERO,
                realizedPnl = netPnl.add(fees).add(funding),
                fees = fees,
                funding = funding,
                fills = emptyList(), // the history endpoint returns aggregate positions, not legs
                raw = row.toString(),
            ),
        )
    }

    private fun parseSide(raw: String?): PositionSide = when (raw?.uppercase()) {
        "SELL", "SHORT", "2" -> PositionSide.SHORT
        else -> PositionSide.LONG
    }

    private companion object {
        const val PATH_HISTORY = "/api/v1/futures/position/get_history_positions"
        const val LIMIT = 100
        const val MAX_PAGES = 50

        val ROW_PATHS = listOf("data", "data.positionList", "data.list")

        // Defensive multi-key candidates — adjust to the verified Bitunix schema.
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
    }
}
