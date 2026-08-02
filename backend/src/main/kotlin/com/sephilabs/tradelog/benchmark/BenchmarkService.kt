// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.benchmark

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Turns stored benchmark closes into buy-and-hold monthly returns, directly comparable to the
 * Monthly ROI bars. No live provider calls happen here — the background refresh owns fetching.
 *
 * A month's return is `close(month end) / close(month start) − 1`, where "month start" is the last
 * close at or before the day *before* the 1st. That mirrors the ROI denominator exactly: TradeLog
 * measures a month against the capital at the START of day 1 (before day-1 trading), so the
 * benchmark must be measured from the price you would have been holding going into day 1. Defined
 * this way consecutive months also chain — their compounded product is the whole-period return,
 * with no move falling between two months.
 *
 * Everything is USD throughout: no FX conversion, so a return only reflects the asset's own
 * performance. A month without usable prices is a gap (null), never a zero.
 */
@Service
class BenchmarkService(
    private val benchmarks: BenchmarkRepository,
    private val prices: BenchmarkPriceRepository,
) {

    /** The enabled benchmarks plus the extent of their stored data, for the chart legend. */
    @Transactional(readOnly = true)
    fun list(): List<BenchmarkDto> = benchmarks.findAllByEnabledTrueOrderBySortOrderAsc().map { b ->
        val from = prices.findMinPriceDate(b.key)
        val to = prices.findMaxPriceDate(b.key)
        BenchmarkDto(key = b.key, hasData = to != null, availableFrom = from, availableTo = to)
    }

    /** Buy-and-hold monthly returns of every enabled benchmark for the twelve months of [year]. */
    @Transactional(readOnly = true)
    fun monthlyReturns(year: Int): List<BenchmarkMonthlySeriesDto> =
        benchmarks.findAllByEnabledTrueOrderBySortOrderAsc().map { buildSeries(it, year) }

    /**
     * Daily closes over [from]..[to] for the requested benchmarks (all of them when [keys] is
     * empty), one entry per calendar day. Callers get levels rather than returns so they can anchor
     * growth wherever they need — the capital chart divides by a different baseline date per
     * exchange, which a pre-normalized series could not express.
     */
    @Transactional(readOnly = true)
    fun dailyCloses(from: LocalDate, to: LocalDate, keys: List<String>): List<BenchmarkDailySeriesDto> {
        if (from.isAfter(to)) return emptyList()
        val dates = from.datesUntil(to.plusDays(1)).toList()
        return benchmarks.findAllByEnabledTrueOrderBySortOrderAsc()
            .filter { keys.isEmpty() || it.key in keys }
            .map { buildDailySeries(it, dates, from, to) }
    }

    private fun buildDailySeries(
        benchmark: Benchmark,
        dates: List<LocalDate>,
        from: LocalDate,
        to: LocalDate,
    ): BenchmarkDailySeriesDto {
        val within = prices.findAllByBenchmarkKeyAndPriceDateBetweenOrderByPriceDateAsc(benchmark.key, from, to)
        // The window can open mid-week (or on a holiday), so the value carried into it comes from
        // the last close before it rather than from the first row inside it.
        val head = prices.findFirstByBenchmarkKeyAndPriceDateLessThanEqualOrderByPriceDateDesc(
            benchmark.key, from.minusDays(1),
        )
        val closes = ForwardFill(listOfNotNull(head) + within)
        // Carrying the last close over a closed market is how a daily series is meant to be read;
        // carrying it indefinitely past the end of the data is not — a discontinued feed would draw
        // a confident flat line forever. Past the grace window the series is simply over.
        val lastUsable = prices.findMaxPriceDate(benchmark.key)?.plusDays(FORWARD_FILL_GRACE_DAYS)
        val points = dates.map { date ->
            val close = if (lastUsable == null || date.isAfter(lastUsable)) null else closes.at(date)
            BenchmarkDailyCloseDto(date, close)
        }
        return BenchmarkDailySeriesDto(
            key = benchmark.key,
            points = points,
            partial = points.any { it.close == null },
        )
    }

    private fun buildSeries(benchmark: Benchmark, year: Int): BenchmarkMonthlySeriesDto {
        val yearStart = LocalDate.of(year, 1, 1)
        val yearEnd = LocalDate.of(year, 12, 31)
        val withinYear = prices.findAllByBenchmarkKeyAndPriceDateBetweenOrderByPriceDateAsc(
            benchmark.key, yearStart, yearEnd,
        )
        // The January anchor lives in the previous year, and after a long provider outage it can be
        // well before 31 Dec — so look it up rather than assuming it sits just outside the window.
        val head = prices.findFirstByBenchmarkKeyAndPriceDateLessThanEqualOrderByPriceDateDesc(
            benchmark.key, yearStart.minusDays(1),
        )
        val closes = ForwardFill(listOfNotNull(head) + withinYear)

        val months = (1..12).map { month ->
            val firstDay = LocalDate.of(year, month, 1)
            val lastDay = firstDay.withDayOfMonth(firstDay.lengthOfMonth())
            BenchmarkMonthlyReturnDto(month, monthlyReturn(closes, withinYear, firstDay, lastDay))
        }
        return BenchmarkMonthlySeriesDto(
            key = benchmark.key,
            months = months,
            partial = months.any { it.ret == null },
        )
    }

    private fun monthlyReturn(
        closes: ForwardFill,
        withinYear: List<BenchmarkPrice>,
        firstDay: LocalDate,
        lastDay: LocalDate,
    ): BigDecimal? {
        // Require a real observation inside the month. Without this, forward-fill would price both
        // ends of a future month (or one past a dead feed) at the same stale close and report a
        // fabricated 0.00 % — the one failure mode that looks like data instead of a gap.
        if (withinYear.none { it.priceDate in firstDay..lastDay }) return null

        val start = closes.at(firstDay.minusDays(1)) ?: return null
        val end = closes.at(lastDay) ?: return null
        if (start.signum() <= 0) return null
        return end.divide(start, RETURN_SCALE, RoundingMode.HALF_EVEN).subtract(BigDecimal.ONE)
    }

    /** Ascending closes; [at] returns the last close at or before a date (the forward fill). */
    private class ForwardFill(rows: List<BenchmarkPrice>) {
        private val points = rows.map { it.priceDate to it.close }.sortedBy { it.first }

        fun at(date: LocalDate): BigDecimal? = points.lastOrNull { !it.first.isAfter(date) }?.second
    }

    companion object {
        /** Matches the capital ROI's fraction scale, so both sides of the chart round alike. */
        const val RETURN_SCALE = 8

        /**
         * How far a close may be carried past the last stored one. Long enough to bridge a weekend
         * plus an adjoining holiday, short enough that a feed which stopped publishing turns into a
         * visible gap instead of a flat line.
         */
        const val FORWARD_FILL_GRACE_DAYS = 5L
    }
}
