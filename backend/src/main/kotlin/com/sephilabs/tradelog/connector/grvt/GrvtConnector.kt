// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector.grvt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.connector.*
import com.sephilabs.tradelog.datasource.SourceKind
import com.sephilabs.tradelog.position.PositionSide
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.HttpCookie
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.SortedMap

/**
 * GRVT perpetual futures. Uses a read-only API key to obtain a session cookie, then queries the
 * consolidated position-history endpoint, which already returns flat-to-flat closed lifecycles.
 */
@Component
class GrvtConnector(
    props: AppProperties,
    mapper: ObjectMapper,
) : ClosedPositionConnector(props.connectors.grvt, mapper, props) {

    override val kind = SourceKind.GRVT

    override val pacingMs = 250L
    override val rateLimitCodes = setOf("1006", "429")
    override val authCodes = setOf("1000", "1001", "1008")
    override val permissionCodes = setOf("1001", "1400", "2000", "2001", "2002", "2003", "2004")

    /** Auth endpoint is on a separate host from the trade API. */
    private val authClient: RestClient = RestClient.builder()
        .baseUrl(AUTH_BASE_URL)
        .build()

    override fun normalizeSymbol(raw: String): Symbol {
        val s = raw.uppercase().removeSuffix("_PERP").removeSuffix("-PERP")
        return Symbols.split(s)
    }

    override fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth {
        val session = sessionFor(creds)
        return Auth(
            headers = mapOf(
                "Cookie" to session.cookie,
                "X-Grvt-Account-Id" to session.accountId,
                "Content-Type" to "application/json",
            ),
        )
    }

    override fun checkEnvelope(root: JsonNode, path: String) {
        // GRVT returns an HTTP status wrapper only on errors; normal responses carry the result directly.
        val code = root.text("code") ?: return
        if (code == "0" || code.isBlank()) return
        failEnvelope(path, code, root.text("message") ?: "")
    }

    override fun fetchRows(creds: ExchangeCredentials, since: Instant?): List<JsonNode> {
        val session = sessionFor(creds)
        val out = mutableListOf<JsonNode>()
        var cursor: String? = null
        var pages = 0
        while (pages < MAX_PAGES) {
            val body = buildPositionHistoryRequest(session.accountId, since, cursor)
            val root = postJson(creds, PATH_POSITION_HISTORY, body)
            val rows = root.rows("result")
            out += rows
            pages++
            cursor = root.text("next")?.takeIf { it.isNotBlank() && rows.isNotEmpty() } ?: break
            pace()
        }
        if (pages >= MAX_PAGES) {
            log.warn("{}: stopped after {} position-history pages", venue, MAX_PAGES)
        }
        return out
    }

    /** Test seam: map a `position_history` result body into canonical positions. */
    internal fun mapRows(node: JsonNode): List<PositionRecord> =
        node.rows("result").mapNotNull { (mapPosition(it) as? Mapped.Ok)?.value }

    override fun mapPosition(row: JsonNode): Mapped<PositionRecord> {
        val instrument = row.text(FIELD_INSTRUMENT) ?: return Mapped.Skip("no instrument")
        val symbol = normalizeSymbol(instrument)
        if (symbol.quote !in DOLLAR_QUOTES) return Mapped.Skip("not dollar-margined (${symbol.quote})")

        val status = row.text(FIELD_STATUS) ?: return Mapped.Skip("no status")
        if (status == STATUS_PARTIALLY_CLOSED) return Mapped.Skip("still open")

        val externalId = "${instrument}-${row.text(FIELD_OPEN_TIME)}-${row.text(FIELD_CLOSE_TIME)}"
        val openedAt = instantNanos(row, FIELD_OPEN_TIME) ?: return Mapped.Skip("no open time")
        val closedAt = instantNanos(row, FIELD_CLOSE_TIME) ?: return Mapped.Skip("no close time")
        val isLong = row.boolean(FIELD_IS_LONG)

        val qty = row.dec(FIELD_CLOSED_VOLUME_BASE)
            ?: row.dec(FIELD_MAX_OPEN_INTEREST_BASE)
            ?: BigDecimal.ZERO
        val entry = row.dec(FIELD_ENTRY_PRICE) ?: BigDecimal.ZERO
        val exit = row.dec(FIELD_EXIT_PRICE) ?: BigDecimal.ZERO
        val realizedPnl = row.dec(FIELD_REALIZED_PNL) ?: BigDecimal.ZERO
        val fees = (row.dec(FIELD_CUMULATIVE_FEE) ?: BigDecimal.ZERO).abs()
        val funding = row.dec(FIELD_CUMULATIVE_FUNDING) ?: BigDecimal.ZERO
        val leverage = row.dec(FIELD_LEVERAGE)?.takeIf { it.signum() > 0 }

        return Mapped.Ok(
            PositionRecord(
                externalId = externalId,
                symbol = symbol,
                side = if (isLong) PositionSide.LONG else PositionSide.SHORT,
                openedAt = openedAt,
                closedAt = closedAt,
                qty = qty,
                entryPrice = entry,
                exitPrice = exit,
                realizedPnl = realizedPnl,
                fees = fees,
                funding = funding,
                pnlCurrency = symbol.quote,
                leverage = leverage,
                raw = row.toString(),
            ),
        )
    }

    /** Logs in once per sync and caches the session for the lifetime of the credentials. */
    private fun sessionFor(creds: ExchangeCredentials): Session {
        val cached = sessions[creds.apiKey]
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) return cached
        val fresh = login(creds)
        sessions[creds.apiKey] = fresh
        return fresh
    }

    private fun login(creds: ExchangeCredentials): Session {
        val response = authClient.post()
            .uri("/auth/api_key/login")
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .header("Cookie", "rm=true;")
            .body(mapOf("api_key" to creds.apiKey.trim()))
            .retrieve()
            .toEntity(String::class.java)
        val body = mapper.readTree(response.body ?: "{}")
        val cookie = response.headers["Set-Cookie"]
            ?.firstNotNullOfOrNull { parseGravityCookie(it) }
            ?: throw AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = "$venue login did not return a session cookie")
        val accountId = response.headers["X-Grvt-Account-Id"]?.firstOrNull()
            ?: body.text("sub_account_id")
            ?: creds.passphrase?.trim()?.takeIf { it.isNotEmpty() }
            ?: creds.apiSecret.trim().takeIf { it.isNotEmpty() }
            ?: throw AppException.badRequest("DATA_SOURCE_PASSPHRASE_REQUIRED", detail = "$venue requires a sub-account ID for this API key")
        return Session(cookie, accountId, Instant.now().plus(SESSION_TTL_MINUTES, ChronoUnit.MINUTES))
    }

    private fun parseGravityCookie(header: String): String? {
        val cookies = HttpCookie.parse(header)
        val gravity = cookies.firstOrNull { it.name == "gravity" } ?: return null
        return "gravity=${gravity.value}"
    }

    private fun buildPositionHistoryRequest(
        accountId: String,
        since: Instant?,
        cursor: String?,
    ): Map<String, Any?> = mapOf(
        "sub_account_id" to accountId,
        "kind" to listOf("PERPETUAL", "STABLE_PERP"),
        "status" to listOf(STATUS_CLOSED, STATUS_LIQUIDATED, STATUS_SETTLED),
        "start_time" to since?.let { it.toEpochMilli() * 1_000_000 },
        "limit" to PAGE_LIMIT,
        "cursor" to cursor,
    )

    private fun instantNanos(row: JsonNode, keys: List<String>): Instant? {
        val text = row.text(keys) ?: return null
        val ns = text.toLongOrNull() ?: return null
        return Instant.ofEpochSecond(ns / 1_000_000_000, ns % 1_000_000_000)
    }

    private fun JsonNode.boolean(keys: List<String>): Boolean =
        text(keys)?.equals("true", ignoreCase = true) ?: path(keys.first()).asBoolean(false)

    private data class Session(
        val cookie: String,
        val accountId: String,
        val expiresAt: Instant,
    )

    private companion object {
        const val AUTH_BASE_URL = "https://edge.grvt.io"
        const val PATH_POSITION_HISTORY = "/full/v1/position_history"
        const val PAGE_LIMIT = 500
        const val MAX_PAGES = 200
        const val SESSION_TTL_MINUTES = 30L

        val DOLLAR_QUOTES = setOf("USDT", "USDC")

        const val STATUS_CLOSED = "CLOSED"
        const val STATUS_LIQUIDATED = "LIQUIDATED"
        const val STATUS_SETTLED = "SETTLED"
        const val STATUS_PARTIALLY_CLOSED = "PARTIALLY_CLOSED"

        val FIELD_INSTRUMENT = listOf("instrument", "i")
        val FIELD_STATUS = listOf("status", "s")
        val FIELD_OPEN_TIME = listOf("open_time", "ot")
        val FIELD_CLOSE_TIME = listOf("close_time", "ct")
        val FIELD_IS_LONG = listOf("is_long", "il")
        val FIELD_ENTRY_PRICE = listOf("entry_price", "ep")
        val FIELD_EXIT_PRICE = listOf("exit_price", "ep1")
        val FIELD_CLOSED_VOLUME_BASE = listOf("closed_volume_base", "cv")
        val FIELD_MAX_OPEN_INTEREST_BASE = listOf("max_open_interest_base", "mo")
        val FIELD_REALIZED_PNL = listOf("realized_pnl", "rp")
        val FIELD_CUMULATIVE_FEE = listOf("cumulative_fee", "cf")
        val FIELD_CUMULATIVE_FUNDING = listOf("cumulative_realized_funding_payment", "cr")
        val FIELD_LEVERAGE = listOf("leverage", "l")

        val sessions = mutableMapOf<String, Session>()
    }
}
