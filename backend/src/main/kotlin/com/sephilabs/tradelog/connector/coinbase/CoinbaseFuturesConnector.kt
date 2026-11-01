// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.coinbase

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.SortedMap

/**
 * Coinbase International perpetual futures on the Deribit/Starbase gateway. A CDP JWT is exchanged
 * for a short-lived Deribit bearer token; positions are reconstructed from fills.
 */
@Component
class CoinbaseFuturesConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ReconstructingConnector(props.connectors.coinbaseFutures, mapper) {

    override val kind = SourceKind.COINBASE_FUTURES

    override val backfillDays = 365L
    override val windowDays = 7L
    override val pacingMs = 250L
    override val derivePnlFromPrices = false

    private val baseUrl = props.connectors.coinbaseFutures.baseUrl

    private val authClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .build()

    override fun normalizeSymbol(raw: String): Symbol {
        val s = raw.uppercase().removeSuffix("-PERPETUAL").removeSuffix("_PERPETUAL")
        return Symbols.split(s)
    }

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        val token = accessToken(creds)
        return Auth(headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"))
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        val error = root.path("error")
        if (error.isMissingNode || error.isNull) return
        val code = error.path("code").asInt(0).toString()
        val msg = error.path("message").asText("")
        failEnvelope(path, code, msg)
    }

    override fun openExposure(creds: ExchangeCredentials): Map<String, BigDecimal>? {
        val body = rpc("private/get_positions", mapOf("currency" to "USDC", "kind" to "future"))
        val rows = postJson(creds, JSON_RPC_PATH, body).rows("result")
        val exposure = mutableMapOf<String, BigDecimal>()
        for (row in rows) {
            val instrument = row.text(FIELD_INSTRUMENT) ?: continue
            val size = row.dec(FIELD_SIZE) ?: continue
            exposure[instrument] = size
        }
        return exposure
    }

    override fun fetchFills(creds: ExchangeCredentials, start: Instant, end: Instant): List<RawFill> {
        val out = mutableListOf<RawFill>()
        var pageStart = start.epochSecond
        val endSec = end.epochSecond
        while (pageStart < endSec) {
            val body = rpc(
                "private/get_user_trades_by_currency_and_time",
                mapOf(
                    "currency" to "USDC",
                    "kind" to "future",
                    "start_timestamp" to (pageStart * 1000L),
                    "end_timestamp" to (endSec * 1000L),
                    "count" to PAGE_SIZE,
                    "sorting" to "asc",
                ),
            )
            val root = postJson(creds, JSON_RPC_PATH, body)
            val page = root.rows("result.trades")
            out += page.mapNotNull { mapTrade(it) }
            if (page.size < PAGE_SIZE) break
            val lastTs = page.lastOrNull()?.long(FIELD_TIMESTAMP_MS)?.div(1000) ?: break
            if (lastTs <= pageStart) break
            pageStart = lastTs + 1
            pace()
        }
        return out
    }

    private fun mapTrade(n: JsonNode): RawFill? {
        val instrument = n.text(FIELD_INSTRUMENT) ?: return null
        if (!instrument.uppercase().endsWith("-PERPETUAL")) return null
        val ts = n.instant(FIELD_TIMESTAMP_MS) ?: return null
        val price = n.dec(FIELD_PRICE) ?: return null
        val amount = n.dec(FIELD_AMOUNT) ?: return null
        val direction = n.text(FIELD_DIRECTION)?.uppercase() ?: return null
        val buy = direction == "BUY"
        return RawFill(
            symbol = instrument,
            ts = ts,
            buy = buy,
            price = price,
            qty = amount,
            fee = (n.dec(FIELD_FEE) ?: BigDecimal.ZERO).abs(),
            realizedPnl = n.dec(FIELD_PROFIT_LOSS) ?: BigDecimal.ZERO,
        )
    }

    private fun accessToken(creds: ExchangeCredentials): String {
        val cached = tokens[creds.apiKey]
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) return cached.token
        val token = exchangeToken(creds)
        tokens[creds.apiKey] = Token(token, Instant.now().plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES))
        return token
    }

    private fun exchangeToken(creds: ExchangeCredentials): String {
        val jwt = CoinbaseJwt.build(
            uri = "POST ${endpointBaseUrl(baseUrl)}${JSON_RPC_PATH}",
            keyName = creds.apiKey.trim(),
            privateKey = creds.apiSecret.trim(),
        )
        val body = rpc("public/auth", mapOf("grant_type" to "coinbase_cdp", "token" to jwt))
        val response = authClient.post()
            .uri(JSON_RPC_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String::class.java)
        val root = mapper.readTree(response ?: "{}")
        checkEnvelope(root, "public/auth")
        return root.path("result").path("access_token").asText()
            ?: throw AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = "$venue login did not return an access token")
    }

    private fun rpc(method: String, params: Map<String, Any?>): Map<String, Any?> = mapOf(
        "jsonrpc" to "2.0",
        "id" to 1,
        "method" to method,
        "params" to params,
    )

    private fun endpointBaseUrl(baseUrl: String): String {
        return baseUrl.removePrefix("https://").removePrefix("http://")
    }

    override fun adjust(record: PositionRecord): PositionRecord = record.copy(pnlCurrency = "USDC")

    private data class Token(val token: String, val expiresAt: Instant)

    private companion object {
        const val JSON_RPC_PATH = "/api/v2"
        const val PAGE_SIZE = 1000
        const val TOKEN_TTL_MINUTES = 14L

        val FIELD_INSTRUMENT = listOf("instrument_name")
        val FIELD_SIZE = listOf("size")
        val FIELD_AMOUNT = listOf("amount")
        val FIELD_PRICE = listOf("price")
        val FIELD_FEE = listOf("fee")
        val FIELD_PROFIT_LOSS = listOf("profit_loss")
        val FIELD_DIRECTION = listOf("direction")
        val FIELD_TIMESTAMP_MS = listOf("timestamp")

        val tokens = mutableMapOf<String, Token>()
    }
}
