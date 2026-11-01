// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.aster

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * Aster perpetual futures. Supports both legacy V1 HMAC keys and V3 Pro API Agent keys.
 * Positions are reconstructed from fills exactly like Binance; funding is tracked separately.
 */
@Component
class AsterConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ReconstructingConnector(props.connectors.aster, mapper) {

    override val kind = SourceKind.ASTER

    override val backfillDays = 90L
    override val windowDays = 7L
    override val pacingMs = 250L
    override val derivePnlFromPrices = false

    private val isV3: (ExchangeCredentials) -> Boolean = { creds ->
        creds.apiKey.trim().startsWith("0x") && !creds.passphrase.isNullOrBlank()
    }

    override fun normalizeSymbol(raw: String): Symbol = Symbols.split(raw.substringBefore(GROUP_SEP))

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        return if (isV3(creds)) {
            authorizeV3(creds, query)
        } else {
            authorizeV1(creds, query)
        }
    }

    private fun authorizeV1(creds: ExchangeCredentials, query: SortedMap<String, String>): Auth {
        query["timestamp"] = Instant.now().toEpochMilli().toString()
        val canonical = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        return Auth(
            query = mapOf("signature" to ExchangeSign.hmacSha256Hex(creds.apiSecret, canonical)),
            headers = mapOf("X-MBX-APIKEY" to creds.apiKey),
        )
    }

    private fun authorizeV3(creds: ExchangeCredentials, query: SortedMap<String, String>): Auth {
        val user = creds.apiKey.trim()
        val signer = creds.passphrase!!.trim()
        val nonce = System.currentTimeMillis() * 1000L + (System.nanoTime() % 1000L)
        query["user"] = user
        query["signer"] = signer
        query["nonce"] = nonce.toString()
        val canonical = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        val signature = AsterSign.sign(canonical, creds.apiSecret)
        return Auth(query = mapOf("signature" to signature))
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        if (root.isArray) return
        val code = root.text("code") ?: return
        failEnvelope(path, code, root.path("msg").asText(""))
    }

    override fun openExposure(creds: ExchangeCredentials): Map<String, BigDecimal>? {
        val path = if (isV3(creds)) PATH_POSITION_RISK_V3 else PATH_POSITION_RISK_V2
        val rows = getJson(creds, path).rows(ROW_ROOT)
        val exposure = mutableMapOf<String, BigDecimal>()
        for (row in rows) {
            val symbol = row.text(FIELD_SYMBOL) ?: continue
            val posAmt = row.dec(FIELD_POSITION_AMT) ?: continue
            exposure[symbol] = posAmt
        }
        return exposure
    }

    override fun fetchFills(creds: ExchangeCredentials, start: Instant, end: Instant): List<RawFill> {
        val incomePath = if (isV3(creds)) PATH_INCOME_V3 else PATH_INCOME_V1
        val tradesPath = if (isV3(creds)) PATH_TRADES_V3 else PATH_TRADES_V1

        val income = pageThrough<Int>(maxPages = MAX_PAGES) { page ->
            val current = page ?: 1
            val rows = getJson(
                creds,
                incomePath,
                sortedMapOf(
                    "startTime" to start.toEpochMilli().toString(),
                    "endTime" to end.toEpochMilli().toString(),
                    "limit" to INCOME_LIMIT.toString(),
                    "page" to current.toString(),
                ),
            ).rows(ROW_ROOT)
            Page(rows, (current + 1).takeIf { rows.size >= INCOME_LIMIT })
        }

        val symbols = income.mapNotNull { row ->
            row.text(FIELD_SYMBOL)?.takeIf { row.text(FIELD_INCOME_TYPE) in TRADING_INCOME }
        }.toSet()

        val out = mutableListOf<RawFill>()
        out += fundingFills(income)
        for (symbol in symbols) {
            pace()
            val rows = pageThrough<Long>(maxPages = MAX_PAGES) { fromId ->
                val params = sortedMapOf("symbol" to symbol, "limit" to TRADES_LIMIT.toString())
                if (fromId != null) {
                    params["fromId"] = fromId.toString()
                } else {
                    params["startTime"] = start.toEpochMilli().toString()
                    params["endTime"] = end.toEpochMilli().toString()
                }
                val page = getJson(creds, tradesPath, params).rows(ROW_ROOT)
                val next = page.lastOrNull()?.long(FIELD_TRADE_ID)?.plus(1)?.takeIf { page.size >= TRADES_LIMIT }
                Page(page, next)
            }
            val skips = SkipTally()
            for (row in rows) skips.keep(row, mapTrade(row), out)
            skips.report(log, venue, "for $symbol in $start..$end", rows.size)
        }
        return out
    }

    private fun mapTrade(n: JsonNode): Mapped<RawFill> {
        val symbol = n.text(FIELD_SYMBOL) ?: return Mapped.Skip("no symbol")
        val quote = normalizeSymbol(symbol).quote
        if (quote !in DOLLAR_QUOTES) return Mapped.Skip("not dollar-margined ($quote)")
        val ts = n.instant(FIELD_TIME) ?: return Mapped.Skip("no timestamp")
        val price = n.dec(FIELD_PRICE) ?: return Mapped.Skip("no price")
        val qty = n.dec(FIELD_QTY) ?: return Mapped.Skip("no qty")
        val side = n.text(FIELD_SIDE)?.uppercase() ?: return Mapped.Skip("no side")
        val positionSide = n.text(FIELD_POSITION_SIDE)?.uppercase() ?: ONE_WAY_POSITION
        return Mapped.Ok(
            RawFill(
                symbol = "$symbol$GROUP_SEP$positionSide",
                ts = ts,
                buy = side == "BUY",
                price = price,
                qty = qty,
                fee = (n.dec(FIELD_COMMISSION) ?: BigDecimal.ZERO).abs(),
                realizedPnl = n.dec(FIELD_REALIZED_PNL) ?: BigDecimal.ZERO,
            ),
        )
    }

    private fun fundingFills(income: List<JsonNode>): List<RawFill> =
        income.mapNotNull { row ->
            if (row.text(FIELD_INCOME_TYPE) != INCOME_FUNDING) return@mapNotNull null
            val symbol = row.text(FIELD_SYMBOL) ?: return@mapNotNull null
            val quote = normalizeSymbol(symbol).quote
            if (quote !in DOLLAR_QUOTES) return@mapNotNull null
            val ts = row.instant(FIELD_TIME) ?: return@mapNotNull null
            val amount = row.dec(FIELD_INCOME) ?: return@mapNotNull null
            val positionSide = row.text(FIELD_POSITION_SIDE)?.uppercase() ?: ONE_WAY_POSITION
            RawFill(
                symbol = "$symbol$GROUP_SEP$positionSide",
                ts = ts,
                buy = true,
                price = BigDecimal.ZERO,
                qty = BigDecimal.ZERO,
                funding = amount.negate(),
            )
        }

    override fun adjust(record: PositionRecord): PositionRecord {
        val quote = record.symbol.quote
        return if (quote in DOLLAR_QUOTES) record.copy(pnlCurrency = quote) else record
    }

    private companion object {
        const val PATH_TRADES_V1 = "/fapi/v1/userTrades"
        const val PATH_TRADES_V3 = "/fapi/v3/userTrades"
        const val PATH_INCOME_V1 = "/fapi/v1/income"
        const val PATH_INCOME_V3 = "/fapi/v3/income"
        const val PATH_POSITION_RISK_V2 = "/fapi/v2/positionRisk"
        const val PATH_POSITION_RISK_V3 = "/fapi/v3/positionRisk"
        const val TRADES_LIMIT = 1000
        const val INCOME_LIMIT = 1000
        const val MAX_PAGES = 50
        const val GROUP_SEP = ' '
        const val ONE_WAY_POSITION = "BOTH"
        const val INCOME_FUNDING = "FUNDING_FEE"

        val DOLLAR_QUOTES = setOf("USDT", "USDC", "USD")
        val TRADING_INCOME = setOf("REALIZED_PNL", "COMMISSION")
        val ROW_ROOT = listOf("")

        val FIELD_SYMBOL = listOf("symbol")
        val FIELD_POSITION_AMT = listOf("positionAmt")
        val FIELD_TIME = listOf("time")
        val FIELD_SIDE = listOf("side")
        val FIELD_POSITION_SIDE = listOf("positionSide")
        val FIELD_PRICE = listOf("price")
        val FIELD_QTY = listOf("qty")
        val FIELD_COMMISSION = listOf("commission")
        val FIELD_REALIZED_PNL = listOf("realizedPnl")
        val FIELD_TRADE_ID = listOf("id")
        val FIELD_INCOME_TYPE = listOf("incomeType")
        val FIELD_INCOME = listOf("income")
    }
}
