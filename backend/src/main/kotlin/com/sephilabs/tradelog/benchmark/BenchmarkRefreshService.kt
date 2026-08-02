// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.benchmark

import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.observability.AppMetrics
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/**
 * Fills `benchmark_prices` for every enabled benchmark. Rows are upserted by
 * (benchmark_key, price_date), so overlapping or repeated runs self-heal. A failing provider never
 * aborts the other benchmarks — the chart simply keeps showing gaps for that one until it recovers.
 *
 * Each run asks for the WHOLE lookback window rather than just the days after the last stored
 * close. That is deliberate, and it is not merely defensive: Yahoo refuses to serve some index
 * symbols over a short recent window (`^CMC200` answers a two-year request in full, but returns
 * nothing at all for "since last week"), so a tail-only fetch would leave those series frozen at
 * whatever date they bootstrapped on, for good. Re-reading the window also repairs interior holes
 * left by a truncated response, which a tail-only fetch can never revisit. The cost is one request
 * per benchmark per night; writes stay proportional to what actually changed.
 */
@Service
class BenchmarkRefreshService(
    private val benchmarks: BenchmarkRepository,
    private val prices: BenchmarkPriceRepository,
    private val source: BenchmarkSource,
    private val metrics: AppMetrics,
    private val props: AppProperties,
    private val txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Refreshes every enabled benchmark up to [today]; failures are isolated per benchmark. */
    fun refresh(today: LocalDate) {
        for (benchmark in benchmarks.findAllByEnabledTrueOrderBySortOrderAsc()) {
            try {
                val written = fill(benchmark, today)
                metrics.benchmarkRefresh(benchmark.key, "success")
                if (written > 0) log.info("Benchmark {}: wrote {} closes", benchmark.key, written)
            } catch (ex: BenchmarkSourceException) {
                metrics.benchmarkRefresh(benchmark.key, "failure")
                log.error("Benchmark refresh failed for {}: {}", benchmark.key, ex.message)
            }
            pace(benchmark)
        }
    }

    /**
     * Reads the provider's whole lookback window and writes only the closes that are new or have
     * been restated, so a steady-state run costs one request and one insert. Returns how many rows
     * were written.
     */
    private fun fill(benchmark: Benchmark, today: LocalDate): Int {
        val from = today.minusDays(props.benchmark.historyLookbackDays)
        val fetched = source.dailyCloses(benchmark, from, today).map { it.canonical() }
        if (fetched.isEmpty()) {
            log.warn("Benchmark {} returned no closes for [{}..{}]", benchmark.key, from, today)
            return 0
        }
        warnIfStale(benchmark, fetched.last().date, today)
        val stored = prices.findAllByBenchmarkKeyAndPriceDateBetweenOrderByPriceDateAsc(benchmark.key, from, today)
            .associate { it.priceDate to it.close }
        // compareTo, not equals: the same value at a different BigDecimal scale is not a change.
        val changed = fetched.filter { stored[it.date]?.compareTo(it.close) != 0 }
        changed.forEach { upsert(benchmark.key, it) }
        return changed.size
    }

    /**
     * A discontinued series does not fail — the provider keeps listing the symbol and answering,
     * it just stops filling in values, which otherwise shows up only as a chart that quietly went
     * blank. (This is exactly how the CMC Crypto 200 index ended.) The window absorbs weekends and
     * market holidays, so anything beyond it means the series really has stopped.
     */
    private fun warnIfStale(benchmark: Benchmark, latest: LocalDate, today: LocalDate) {
        if (latest.isBefore(today.minusDays(STALE_AFTER_DAYS))) {
            log.warn("Benchmark {} looks discontinued: newest close is {}", benchmark.key, latest)
        }
    }

    private fun upsert(key: String, day: DailyClose) {
        TransactionTemplate(txManager).execute {
            val existing = prices.findByBenchmarkKeyAndPriceDate(key, day.date)
            if (existing != null) {
                existing.close = day.close
                existing.fetchedAt = Instant.now()
                prices.save(existing)
            } else {
                try {
                    prices.save(BenchmarkPrice(benchmarkKey = key, priceDate = day.date, close = day.close))
                } catch (ignored: DataIntegrityViolationException) {
                    // Race with a concurrent fill: the row exists now, nothing to do.
                }
            }
        }
    }

    /** Spaces successive provider calls so a full refresh stays well clear of any rate limiting. */
    private fun pace(benchmark: Benchmark) {
        val interval = when (benchmark.kind) {
            BenchmarkKind.equity -> props.benchmark.yahoo.minRequestIntervalMs
            BenchmarkKind.crypto -> props.benchmark.binance.minRequestIntervalMs
        }
        if (interval <= 0) return
        try {
            Thread.sleep(interval)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Rounds a close to the precision the column actually stores. Yahoo serves 32-bit quotes widened
     * to double, so a close arrives as 189.44000244140625 while NUMERIC(28,12) keeps
     * 189.440002441406. Without canonicalising first, every fetched row compares unequal to its own
     * stored form and the job rewrites the entire window every single night.
     */
    private fun DailyClose.canonical(): DailyClose =
        copy(close = close.setScale(CLOSE_SCALE, RoundingMode.HALF_EVEN))

    companion object {
        /** Matches `benchmark_prices.close` — NUMERIC(28,12). */
        const val CLOSE_SCALE = 12

        /** Comfortably past any weekend or market-holiday stretch. */
        const val STALE_AFTER_DAYS = 10L
    }
}
