// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.coinbase

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * Coinbase Advanced Trade v3 REST futures / perpetual-style products (EU / CFM). Closed positions
 * are reconstructed from historical fills; only USDC/USD-margined products are kept.
 */
@Component
class CoinbaseEuFuturesConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ReconstructingConnector(props.connectors.coinbaseEu, mapper) {

    override val kind = SourceKind.COINBASE_EU_FUTURES

    override val backfillDays = 365L
    override val windowDays = 7L
    override val pacingMs = 250L
    override val derivePnlFromPrices = true

    override fun normalizeSymbol(raw: String): Symbol {
        val s = raw.uppercase()
        return when {
            s.endsWith("-PERP-INTX") || s.endsWith("-PERP") -> {
                val base = s.substringBefore("-PERP")
                Symbol(base, "USDC")
            }
            s.startsWith("BIP-") || s.contains("-CDE") -> {
                // Dated / index futures settle in USD on CFM.
                val base = s.removePrefix("BIP-").substringBefore("-")
                Symbol(base, "USD")
            }
            else -> Symbols.split(s)
        }
    }

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        val jwt = CoinbaseJwt.build(
            uri = "$method api.coinbase.com$path?${query.entries.joinToString("&") { "${it.key}=${it.value}" }}",
            keyName = creds.apiKey.trim(),
            privateKey = creds.apiSecret.trim(),
        )
        return Auth(headers = mapOf("Authorization" to "Bearer $jwt"))
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        val error = root.path("error")
        if (error.isMissingNode || error.isNull) return
        val code = error.text("error_code") ?: "unknown"
        val msg = error.text("message") ?: ""
        if (code == "proof_token_required") {
            throw AppException.badRequest(
                "DATA_SOURCE_PERMISSION_DENIED",
                detail = "$venue requires EU SCA proof_token for this account; unattended API reads are blocked",
            )
        }
        failEnvelope(path, code, msg)
    }

    override fun openExposure(creds: ExchangeCredentials): Map<String, BigDecimal>? {
        // Try CFM positions first, then INTX. Both only return open positions; missing is fine.
        val exposure = mutableMapOf<String, BigDecimal>()
        try {
            val cfm = getJson(creds, "/api/v3/brokerage/cfm/positions").rows("positions")
            for (row in cfm) {
                val symbol = row.text(FIELD_PRODUCT_ID) ?: continue
                val size = row.dec(FIELD_AMOUNT) ?: row.dec(FIELD_SIZE) ?: continue
                exposure[symbol] = size
            }
        } catch (e: Exception) {
            log.debug("{} CFM positions unavailable: {}", venue, e.message)
        }
        return exposure.ifEmpty { null }
    }

    override fun fetchFills(creds: ExchangeCredentials, start: Instant, end: Instant): List<RawFill> {
        val out = mutableListOf<RawFill>()
        var cursor: String? = null
        var pages = 0
        val startMs = start.toEpochMilli().toString()
        val endMs = end.toEpochMilli().toString()
        while (pages < MAX_PAGES) {
            val params = mutableMapOf(
                "product_types" to "FUTURE",
                "start_sequence_timestamp" to startMs,
                "end_sequence_timestamp" to endMs,
                "limit" to PAGE_LIMIT.toString(),
            )
            cursor?.let { params["cursor"] = it }
            val root = getJson(creds, PATH_FILLS, params)
            val page = root.rows(FILL_PATHS)
            out += page.mapNotNull { mapFill(it) }
            pages++
            cursor = root.text("cursor")?.takeIf { it.isNotBlank() && page.isNotEmpty() } ?: break
            pace()
        }
        if (pages >= MAX_PAGES) {
            log.warn("{}: stopped after {} fill pages", venue, MAX_PAGES)
        }
        return out
    }

    private fun mapFill(n: JsonNode): RawFill? {
        val productId = n.text(FIELD_PRODUCT_ID) ?: return null
        val symbol = normalizeSymbol(productId)
        if (symbol.quote !in DOLLAR_QUOTES) return null
        val ts = n.instant(FIELD_TRADE_TIME) ?: return null
        val side = n.text(FIELD_SIDE)?.uppercase() ?: return null
        val price = n.dec(FIELD_PRICE) ?: return null
        val size = n.dec(FIELD_SIZE) ?: return null
        return RawFill(
            symbol = productId,
            ts = ts,
            buy = side == "BUY_SIDE" || side == "BUY",
            price = price,
            qty = size,
            fee = (n.dec(FIELD_COMMISSION) ?: BigDecimal.ZERO).abs(),
        )
    }

    override fun adjust(record: PositionRecord): PositionRecord {
        val quote = record.symbol.quote
        return if (quote in DOLLAR_QUOTES) record.copy(pnlCurrency = quote) else record
    }

    private companion object {
        const val PATH_FILLS = "/api/v3/brokerage/orders/historical/fills"
        const val PAGE_LIMIT = 100
        const val MAX_PAGES = 200

        val DOLLAR_QUOTES = setOf("USDC", "USD")
        val FILL_PATHS = listOf("fills", "fills_response.fills")

        val FIELD_PRODUCT_ID = listOf("product_id")
        val FIELD_SIDE = listOf("side")
        val FIELD_PRICE = listOf("price")
        val FIELD_SIZE = listOf("size", "amount")
        val FIELD_AMOUNT = listOf("amount", "size")
        val FIELD_COMMISSION = listOf("commission")
        val FIELD_TRADE_TIME = listOf("trade_time")
    }
}
