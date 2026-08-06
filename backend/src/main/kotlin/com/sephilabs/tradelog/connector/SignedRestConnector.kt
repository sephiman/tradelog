// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.config.AppProperties
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.util.SortedMap

/** Shared HTTP plumbing for the signed-REST connectors: one signed GET, retries, error mapping. */
abstract class SignedRestConnector(
    endpoint: AppProperties.ExchangeEndpoint,
    protected val mapper: ObjectMapper,
) : ApiConnector {

    protected val log: Logger = LoggerFactory.getLogger(javaClass)
    protected val client: RestClient = ExchangeHttp.restClient(endpoint)

    /** How this venue is named in logs and error details. */
    protected val venue: String get() = kind.venueLabel ?: kind.name

    /** Extra query parameters and headers that authenticate one request. */
    data class Auth(
        val query: Map<String, String> = emptyMap(),
        val headers: Map<String, String> = emptyMap(),
    )

    /** Sign one request; [query] is the sorted map that will be sent. Re-run per attempt. */
    protected abstract fun authorize(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        query: SortedMap<String, String>,
    ): Auth

    /** Throw if the venue's JSON envelope reports failure; `SYNC_RATE_LIMITED` is retried. */
    protected open fun checkEnvelope(root: JsonNode, path: String) {}

    /** Business codes meaning "bad key/signature" — reported so the user knows to rotate the key. */
    protected open val authCodes: Set<String> = emptySet()

    /** Business codes meaning "this key lacks the read permission" (or has the wrong scope). */
    protected open val permissionCodes: Set<String> = emptySet()

    /** Business codes meaning "too many requests" — retried here rather than failing the sync. */
    protected open val rateLimitCodes: Set<String> = emptySet()

    /** Delay held between successive calls of a multi-window backfill, to stay inside the quota. */
    protected open val pacingMs: Long = DEFAULT_PACING_MS

    protected open val maxRetries: Int = DEFAULT_MAX_RETRIES

    /** Signed GET; retries rate limiting with exponential backoff, maps anything else to an error. */
    protected fun getJson(
        creds: ExchangeCredentials,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): JsonNode = send(creds, "GET", path, params) { path, query, auth ->
        client.get()
            .uri { b ->
                b.path(path)
                query.forEach { (k, v) -> b.queryParam(k, v) }
                auth.query.forEach { (k, v) -> b.queryParam(k, v) }
                b.build()
            }
            .headers { h -> auth.headers.forEach { (k, v) -> h.set(k, v) } }
            .retrieve()
            .body(String::class.java)
    }

    /** Signed POST with a form body (Kraken spot); [authorize] still sees the params as `query`. */
    protected fun postForm(
        creds: ExchangeCredentials,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): JsonNode = send(creds, "POST", path, params) { path, query, auth ->
        val form = LinkedMultiValueMap<String, String>()
        query.forEach { (k, v) -> form.add(k, v) }
        auth.query.forEach { (k, v) -> form.add(k, v) }
        client.post()
            .uri { b -> b.path(path).build() }
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .headers { h -> auth.headers.forEach { (k, v) -> h.set(k, v) } }
            .body(form)
            .retrieve()
            .body(String::class.java)
    }

    /** Signs, sends and validates one request, retrying while the venue says it is rate limited. */
    private fun send(
        creds: ExchangeCredentials,
        method: String,
        path: String,
        params: Map<String, String>,
        exchange: (String, SortedMap<String, String>, Auth) -> String?,
    ): JsonNode {
        var attempt = 0
        while (true) {
            val query = sortedMapOf<String, String>().apply { putAll(params) }
            val auth = authorize(creds, method, path, query)
            log.debug("{} request: {} {} params={} attempt={}", venue, method, path, params, attempt)
            val body = try {
                exchange(path, query, auth)
            } catch (e: RestClientResponseException) {
                if (e.statusCode.value() == HTTP_TOO_MANY && attempt < maxRetries) {
                    backoff(++attempt, "HTTP $HTTP_TOO_MANY")
                    continue
                }
                log.warn(
                    "{} HTTP error: path={} status={} body={}",
                    venue, path, e.statusCode.value(), e.responseBodyAsString.take(ERROR_BODY_CHARS),
                )
                throw httpError(e)
            }
            val root = mapper.readTree(body ?: "{}")
            try {
                checkEnvelope(root, path)
            } catch (e: AppException) {
                if (e.code == RATE_LIMITED_CODE && attempt < maxRetries) {
                    backoff(++attempt, e.message ?: RATE_LIMITED_CODE)
                    continue
                }
                throw e
            }
            return root
        }
    }

    /** Keyless GET of public reference data. Best-effort: null on failure, callers fall back. */
    protected fun publicJson(path: String, params: Map<String, String> = emptyMap()): JsonNode? =
        try {
            val body = client.get()
                .uri { b -> b.path(path).also { params.forEach { (k, v) -> b.queryParam(k, v) } }.build() }
                .retrieve()
                .body(String::class.java)
            mapper.readTree(body ?: "{}")
        } catch (e: Exception) {
            log.warn("{} public read of {} failed; using defaults: {}", venue, path, e.message)
            null
        }

    /** Pages until [fetch] reports no continuation. The token can be a cursor, page number or offset. */
    protected fun <T : Any> pageThrough(
        maxPages: Int = DEFAULT_MAX_PAGES,
        paced: Boolean = true,
        fetch: (T?) -> Page<T>,
    ): List<JsonNode> {
        val out = mutableListOf<JsonNode>()
        var token: T? = null
        var page = 0
        var exhausted = false
        while (page < maxPages) {
            val result = fetch(token)
            out += result.rows
            page++
            token = result.next
            if (result.rows.isEmpty() || token == null) {
                exhausted = true
                break
            }
            if (paced) pace()
        }
        if (!exhausted) {
            // A silent truncation would read as complete history, so never let the cap pass quietly.
            log.warn("{}: stopped after the {}-page cap with more data available", venue, maxPages)
        }
        return out
    }

    /** One page of rows plus the token for the next one; a null [next] ends the walk. */
    data class Page<T>(val rows: List<JsonNode>, val next: T?)

    /** Hold [pacingMs] between successive calls so a multi-window backfill stays under the quota. */
    protected fun pace(more: Boolean = true) {
        if (!more || pacingMs <= 0) return
        try {
            Thread.sleep(pacingMs)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun backoff(attempt: Int, reason: String) {
        val ms = RETRY_BASE_MS shl (attempt - 1)
        log.warn("{} rate limited ({}), retry {}/{} after {}ms", venue, reason, attempt, maxRetries, ms)
        try {
            Thread.sleep(ms)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AppException.badRequest("SYNC_FAILED", detail = "Interrupted during $venue backoff", cause = ie)
        }
    }

    /** Map a venue business code to the error the user can act on. */
    protected fun businessError(code: String, msg: String): AppException {
        val detail = "$venue code=$code msg=$msg"
        return when (code) {
            in authCodes -> AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = detail)
            in permissionCodes -> AppException.badRequest("DATA_SOURCE_PERMISSION_DENIED", detail = detail)
            in rateLimitCodes -> AppException.tooManyRequests(RATE_LIMITED_CODE)
            else -> AppException.badRequest("SYNC_FAILED", detail = detail)
        }
    }

    /** Report the envelope's own code/message, retrying or failing as [businessError] decides. */
    protected fun failEnvelope(path: String, code: String, msg: String): Nothing {
        log.warn("{} business error: path={} code={} msg={}", venue, path, code, msg)
        throw businessError(code, msg)
    }

    protected open fun httpError(e: RestClientResponseException): AppException {
        val detail = "$venue HTTP ${e.statusCode.value()}: ${e.responseBodyAsString.take(ERROR_DETAIL_CHARS)}"
        return when (e.statusCode.value()) {
            401 -> AppException.badRequest("DATA_SOURCE_CREDENTIALS_INVALID", detail = detail, cause = e)
            403 -> AppException.badRequest("DATA_SOURCE_PERMISSION_DENIED", detail = detail, cause = e)
            HTTP_TOO_MANY -> AppException.tooManyRequests(RATE_LIMITED_CODE)
            else -> AppException.badRequest("SYNC_FAILED", detail = detail, cause = e)
        }
    }

    /** The passphrase a venue requires as a third credential, or a clear error if it is missing. */
    protected fun requirePassphrase(creds: ExchangeCredentials): String =
        creds.passphrase?.takeIf { it.isNotBlank() }
            ?: throw AppException.badRequest(
                "DATA_SOURCE_PASSPHRASE_REQUIRED",
                detail = "$venue requires an API passphrase",
            )

    protected companion object {
        const val RATE_LIMITED_CODE = "SYNC_RATE_LIMITED"
        const val HTTP_TOO_MANY = 429
        const val DEFAULT_MAX_RETRIES = 4
        const val RETRY_BASE_MS = 500L
        const val DEFAULT_PACING_MS = 250L
        const val DEFAULT_MAX_PAGES = 200
        const val ERROR_BODY_CHARS = 500
        const val ERROR_DETAIL_CHARS = 300
    }
}
