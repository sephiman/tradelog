// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.bitmart

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.SortedMap

/**
 * BitMart USDT-M perpetuals, reconstructed from fills. Private reads are KEYED, not signed — only the
 * API key travels. BitMart closes on 2026-08-26, after which the source freezes itself.
 */
@Component
class BitmartConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ReconstructingConnector(props.connectors.bitmart, mapper) {

    override val kind = SourceKind.BITMART

    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw)

    // ~6 req/2s on private contract reads ⇒ keep ≥333ms between calls.
    override val pacingMs = 350L
    override val rateLimitCodes = setOf("40016", "429") // "too many requests" (verify against live errors)
    override val authCodes = setOf("40001", "40002", "40012") // signature / api-key errors
    override val permissionCodes = setOf("40013", "40014") // permission denied

    /** Contract sizes change with listings, not with trades, so one read serves many syncs. */
    private val contractSizes = Memo(Duration.ofHours(6)) {
        ContractSizes.from(
            publicJson(PATH_DETAILS),
            rowPaths = listOf("data.symbols"),
            symbolKeys = FIELD_SYMBOL,
            sizeKeys = FIELD_CONTRACT_SIZE,
        ).also { log.debug("BitMart contract details: {} symbols sized", it.size) }
    }

    /** Keyed, not signed: only the API key travels, in a header. */
    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth = Auth(headers = mapOf("X-BM-KEY" to creds.apiKey))

    override fun checkEnvelope(root: JsonNode, path: String) {
        val code = root.path("code").asInt(SUCCESS_CODE)
        if (code == SUCCESS_CODE) return
        val msg = root.path("message").asText(root.path("msg").asText(""))
        failEnvelope(path, code.toString(), msg)
    }

    /** 200 rows max and no paging parameter, so a window at the cap is halved until it fits. */
    override fun fetchFills(creds: ExchangeCredentials, start: Instant, end: Instant): List<RawFill> {
        val sizes = contractSizes.get()
        val node = getJson(
            creds,
            PATH_TRADES,
            mapOf(
                // Epoch SECONDS on this endpoint, unlike the millisecond `create_time` it returns.
                "start_time" to start.epochSecond.toString(),
                "end_time" to end.epochSecond.toString(),
            ),
        )
        val trades = node.rows(ROW_PATHS)
        if (trades.size >= PAGE_LIMIT && end.toEpochMilli() - start.toEpochMilli() > MIN_SPLIT_MS) {
            val mid = Instant.ofEpochMilli((start.toEpochMilli() + end.toEpochMilli()) / 2)
            log.info("BitMart window {}..{} hit the {}-row cap; splitting at {}", start, end, PAGE_LIMIT, mid)
            pace()
            // Older half first keeps the overall walk strictly backward in time.
            return fetchFills(creds, start, mid) + fetchFills(creds, mid, end)
        }
        if (trades.size >= PAGE_LIMIT) {
            log.warn(
                "BitMart window {}..{} returned {} rows at the min split span — some fills may be dropped",
                start, end, trades.size,
            )
        }
        val skips = SkipTally()
        val mapped = mutableListOf<RawFill>()
        for (row in trades) skips.keep(row, mapFill(row, sizes), mapped)
        skips.report(log, venue, "in window $start..$end", trades.size)
        return mapped
    }

    /** Test seam: map a raw `/contract/private/trades` body into fills, dropping unmappable rows. */
    internal fun mapTrades(node: JsonNode, sizes: ContractSizes): List<RawFill> =
        node.rows(ROW_PATHS).mapNotNull { (mapFill(it, sizes) as? Mapped.Ok)?.value }

    private fun mapFill(n: JsonNode, sizes: ContractSizes): Mapped<RawFill> {
        val symbol = n.text(FIELD_SYMBOL) ?: return Mapped.Skip("no symbol")
        val ts = n.instant(FIELD_TIME) ?: return Mapped.Skip("no timestamp")
        val side = n.int(FIELD_SIDE) ?: return Mapped.Skip("no side")
        val price = n.dec(FIELD_PRICE) ?: return Mapped.Skip("no price")
        val vol = n.dec(FIELD_VOL) ?: return Mapped.Skip("no vol")
        if (side !in 1..4) return Mapped.Skip("bad side=$side")
        return Mapped.Ok(
            RawFill(
                symbol = symbol,
                ts = ts,
                buy = side == 1 || side == 2,
                price = price,
                qty = vol.multiply(sizes.of(symbol)),
                fee = (n.dec(FIELD_FEE) ?: BigDecimal.ZERO).abs(),
                realizedPnl = n.dec(FIELD_PNL) ?: BigDecimal.ZERO,
                // Funding is not part of /contract/private/trades; left zero (it could be added later
                // from /contract/private/transaction-history with flow_type=3).
                funding = BigDecimal.ZERO,
            ),
        )
    }

    private companion object {
        const val PATH_TRADES = "/contract/private/trades"
        const val PATH_DETAILS = "/contract/public/details"
        const val PAGE_LIMIT = 200 // max rows per response; there is no paging parameter
        const val MIN_SPLIT_MS = 60L * 60L * 1000L // don't split a window below ~1h chasing the cap
        const val SUCCESS_CODE = 1000

        val ROW_PATHS = listOf("data", "data.trades")
        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_SIDE = listOf("side")
        val FIELD_PRICE = listOf("price", "deal_avg_price")
        val FIELD_VOL = listOf("vol", "deal_size")
        val FIELD_FEE = listOf("paid_fees", "paid_fee", "fee")
        val FIELD_PNL = listOf("realised_profit", "realized_profit", "profit_amount")
        val FIELD_TIME = listOf("create_time", "createTime", "ctime", "timestamp")
        val FIELD_CONTRACT_SIZE = listOf("contract_size", "contractSize")
    }
}
