// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.benchmark

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.sephilabs.tradelog.config.AppProperties
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** One daily close of a benchmark, in USD. */
data class DailyClose(val date: LocalDate, val close: BigDecimal)

/** A benchmark price provider was unreachable or answered with an error. */
class BenchmarkSourceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Fetches a benchmark's daily closes over [from]..[to], in USD. */
interface BenchmarkSource {
    fun dailyCloses(benchmark: Benchmark, from: LocalDate, to: LocalDate): List<DailyClose>
}

/**
 * Default benchmark source, deliberately keyless: equity/commodity/index closes come from Yahoo's
 * public chart endpoint (`^GSPC`, `GC=F`, `URTH`, `^CMC200`), crypto from Binance USDT klines.
 * Kept independent of the exchange connectors — benchmarks are reference data, not user trades.
 */
@Component
class DefaultBenchmarkSource(
    private val yahoo: YahooChartClient,
    private val binance: BinanceKlinesClient,
) : BenchmarkSource {

    override fun dailyCloses(benchmark: Benchmark, from: LocalDate, to: LocalDate): List<DailyClose> =
        when (benchmark.kind) {
            BenchmarkKind.equity -> yahoo.dailyCloses(benchmark.sourceSymbol, from, to)
            BenchmarkKind.crypto -> binance.dailyCloses(benchmark.sourceSymbol, from, to)
        }
}

/**
 * Yahoo Finance chart adapter (UNOFFICIAL, keyless): no quota, long history, USD quotes for the
 * symbols we track. A ToS-gray endpoint that can change without notice, so calls send a realistic
 * User-Agent and a transport failure retries once against the query2 host.
 */
@Component
class YahooChartClient(props: AppProperties) {

    private val primary = restClient(props.benchmark.yahoo.baseUrl, props.benchmark.yahoo.timeoutMs)
    private val fallback = restClient(props.benchmark.yahoo.fallbackBaseUrl, props.benchmark.yahoo.timeoutMs)

    // All fields nullable: Jackson maps absent keys to null rather than Kotlin defaults, and Yahoo
    // omits keys freely — a symbol with no data in the window returns a result with no `timestamp`.
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChartResponse(val chart: Chart? = null) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Chart(val result: List<Result>? = null, val error: ChartError? = null)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class ChartError(val code: String? = null, val description: String? = null)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Result(
            val meta: Meta? = null,
            val timestamp: List<Long>? = null,
            val indicators: Indicators? = null,
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Meta(val currency: String? = null)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Indicators(val quote: List<Quote>? = null)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Quote(val close: List<BigDecimal?>? = null)
    }

    fun dailyCloses(symbol: String, from: LocalDate, to: LocalDate): List<DailyClose> = call(symbol) { rest ->
        val response = rest.get()
            .uri { builder ->
                builder.path("/v8/finance/chart/{symbol}")
                    .queryParam("period1", from.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                    .queryParam("period2", to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                    .queryParam("interval", "1d")
                    .build(symbol)
            }
            .retrieve()
            .body(ChartResponse::class.java)
        response?.chart?.error?.let {
            throw BenchmarkSourceException("Yahoo chart error for $symbol: ${it.code} ${it.description}")
        }
        val result = response?.chart?.result?.firstOrNull() ?: return@call emptyList()
        // A quoted currency other than USD would make the return incomparable, and no amount of
        // downstream care can recover it — refuse rather than store a silently wrong series.
        val currency = result.meta?.currency?.uppercase()
        if (currency != null && currency != "USD") {
            throw BenchmarkSourceException("Yahoo quotes $symbol in $currency, expected USD")
        }
        val timestamps = result.timestamp.orEmpty()
        // Raw close, not adjusted: an index level is the quote itself.
        val closes = result.indicators?.quote?.firstOrNull()?.close.orEmpty()
        timestamps.zip(closes)
            .mapNotNull { (ts, close) ->
                close ?: return@mapNotNull null
                DailyClose(Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate(), close)
            }
            // An intraday run can return two points for the same UTC day; keep the later one.
            .groupBy { it.date }
            .map { (_, points) -> points.last() }
            .sortedBy { it.date }
    }

    /** Runs [block] against query1, retrying once against query2 on transport failure. */
    private fun <T> call(symbol: String, block: (RestClient) -> T): T =
        try {
            block(primary)
        } catch (ex: BenchmarkSourceException) {
            throw ex
        } catch (primaryFailure: Exception) {
            try {
                block(fallback)
            } catch (ex: BenchmarkSourceException) {
                throw ex
            } catch (fallbackFailure: Exception) {
                throw BenchmarkSourceException(
                    "Yahoo chart for $symbol failed on both hosts: ${primaryFailure.message}",
                    fallbackFailure,
                )
            }
        }

    private fun restClient(baseUrl: String, timeoutMs: Long): RestClient {
        val timeout = Duration.ofMillis(timeoutMs)
        val factory = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build())
        factory.setReadTimeout(timeout)
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
            .build()
    }

    companion object {
        // Yahoo rejects default HTTP-client agents; a realistic browser UA is mandatory.
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}

/**
 * Binance public market-data adapter (keyless) for the crypto benchmarks. USDT closes are treated
 * as USD — the same assumption TradeLog's USDT-denominated capital already makes. An unlisted pair
 * returns empty rather than failing the whole refresh.
 */
@Component
class BinanceKlinesClient(props: AppProperties) {

    private val rest: RestClient = run {
        val timeout = Duration.ofMillis(props.benchmark.binance.timeoutMs)
        val factory = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build())
        factory.setReadTimeout(timeout)
        RestClient.builder().baseUrl(props.benchmark.binance.baseUrl).requestFactory(factory).build()
    }

    fun dailyCloses(pair: String, from: LocalDate, to: LocalDate): List<DailyClose> {
        val closes = mutableListOf<DailyClose>()
        var cursor = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val endMs = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
        try {
            // Klines are capped at 1000 rows per call; page forward until the range is covered.
            while (cursor <= endMs) {
                val klines = rest.get()
                    .uri { builder ->
                        builder.path("/api/v3/klines")
                            .queryParam("symbol", pair)
                            .queryParam("interval", "1d")
                            .queryParam("startTime", cursor)
                            .queryParam("endTime", endMs)
                            .queryParam("limit", PAGE_SIZE)
                            .build()
                    }
                    .retrieve()
                    .body(object : ParameterizedTypeReference<List<List<Any>>>() {})
                    .orEmpty()
                if (klines.isEmpty()) break
                for (kline in klines) {
                    // Kline layout: [0] = open time (ms), [4] = close (string).
                    if (kline.size <= CLOSE_INDEX) continue
                    val openTime = (kline[0] as? Number)?.toLong() ?: continue
                    val close = runCatching { BigDecimal(kline[CLOSE_INDEX].toString()) }.getOrNull() ?: continue
                    closes += DailyClose(
                        Instant.ofEpochMilli(openTime).atZone(ZoneOffset.UTC).toLocalDate(),
                        close,
                    )
                }
                if (klines.size < PAGE_SIZE) break
                cursor = ((klines.last()[0] as? Number)?.toLong() ?: break) + DAY_MS
            }
        } catch (ex: RestClientResponseException) {
            // -1121 = invalid symbol: the pair simply is not listed, so there is no data to store.
            if (ex.statusCode.is4xxClientError && ex.responseBodyAsString.contains("-1121")) return emptyList()
            throw BenchmarkSourceException("Binance klines failed for $pair: ${ex.message}", ex)
        } catch (ex: Exception) {
            throw BenchmarkSourceException("Binance klines failed for $pair: ${ex.message}", ex)
        }
        return closes.sortedBy { it.date }
    }

    companion object {
        const val PAGE_SIZE = 1000
        const val CLOSE_INDEX = 4
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
