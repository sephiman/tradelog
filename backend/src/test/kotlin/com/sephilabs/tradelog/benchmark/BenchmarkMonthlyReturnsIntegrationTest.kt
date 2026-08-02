// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.benchmark

import com.sephilabs.tradelog.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Exercises the buy-and-hold monthly-return rules against a real database, with prices seeded
 * directly — no test ever calls a price provider (the test profile disables both the startup
 * backfill and the nightly job).
 */
class BenchmarkMonthlyReturnsIntegrationTest @Autowired constructor(
    private val service: BenchmarkService,
    private val benchmarks: BenchmarkRepository,
    private val prices: BenchmarkPriceRepository,
) : IntegrationTestBase() {

    private val key = "gold" // any seeded benchmark; the maths is registry-independent

    @BeforeEach
    fun clearPrices() {
        prices.deleteAll()
    }

    private fun seed(date: String, close: String) {
        prices.save(BenchmarkPrice(benchmarkKey = key, priceDate = LocalDate.parse(date), close = BigDecimal(close)))
    }

    private fun monthsOf(year: Int): Map<Int, BigDecimal?> =
        service.monthlyReturns(year).first { it.key == key }.months.associate { it.month to it.ret }

    private fun assertPct(actual: BigDecimal?, expectedPct: Double) {
        assertThat(actual).isNotNull()
        assertThat(actual!!.toDouble() * 100).isCloseTo(expectedPct, within(1e-6))
    }

    @Test
    fun `month return is the close at month end over the close carried into the month`() {
        seed("2025-12-31", "100")
        seed("2026-01-15", "105")
        seed("2026-01-31", "110")

        assertPct(monthsOf(2026)[1], 10.0)
    }

    @Test
    fun `the start price is the previous month's last close, not the month's first close`() {
        // Using the in-month first close (102) would silently drop the 31 Dec -> 2 Jan move.
        seed("2025-12-31", "100")
        seed("2026-01-02", "102")
        seed("2026-01-30", "120")

        assertPct(monthsOf(2026)[1], 20.0)
    }

    @Test
    fun `consecutive months chain to the whole-period return with no move falling between them`() {
        seed("2025-12-31", "100")
        seed("2026-01-31", "110")
        seed("2026-02-28", "121")
        seed("2026-03-31", "133.1")

        val months = monthsOf(2026)
        val compounded = (1..3).fold(BigDecimal.ONE) { acc, m -> acc * (BigDecimal.ONE + months.getValue(m)!!) }

        assertPct(months[1], 10.0)
        assertPct(months[2], 10.0)
        assertPct(months[3], 10.0)
        // 133.1 / 100 - 1 = +33.1 %, recovered exactly from the three monthly figures.
        assertThat((compounded - BigDecimal.ONE).toDouble() * 100).isCloseTo(33.1, within(1e-6))
    }

    @Test
    fun `a month whose last days are non-trading forward-fills from the last close within it`() {
        // A Mon-Fri index: January 2026 ends on a Saturday, so the 30th is the month's last close.
        seed("2025-12-31", "100")
        seed("2026-01-30", "108")

        assertPct(monthsOf(2026)[1], 8.0)
    }

    @Test
    fun `a month with no prices of its own is a gap, never a fabricated zero`() {
        // Both endpoints would forward-fill to 110 and report 0.00 % — the one failure mode that
        // looks like real data. February has no observation, so it must stay a gap.
        seed("2025-12-31", "100")
        seed("2026-01-31", "110")
        seed("2026-03-31", "121")

        val months = monthsOf(2026)
        assertPct(months[1], 10.0)
        assertThat(months[2]).isNull()
        // March still measures from January's close: the price did move, over two months.
        assertPct(months[3], 10.0)
    }

    @Test
    fun `months after the last stored close are gaps, so a stale feed never reads as flat`() {
        seed("2025-12-31", "100")
        seed("2026-01-31", "110")

        val months = monthsOf(2026)
        assertPct(months[1], 10.0)
        assertThat(months.filterKeys { it >= 2 }.values).allSatisfy { assertThat(it).isNull() }
    }

    @Test
    fun `the first month of a series is a gap, because the price carried into it is unknown`() {
        seed("2026-01-05", "100")
        seed("2026-01-31", "110")
        seed("2026-02-27", "121")

        val months = monthsOf(2026)
        assertThat(months[1]).isNull()
        assertPct(months[2], 10.0)
    }

    @Test
    fun `a negative month is reported as a negative return`() {
        seed("2025-12-31", "200")
        seed("2026-01-31", "150")

        assertPct(monthsOf(2026)[1], -25.0)
    }

    @Test
    fun `partial flags a series with any gap, and list reports the stored extent`() {
        seed("2025-12-31", "100")
        seed("2026-01-31", "110")

        val series = service.monthlyReturns(2026).first { it.key == key }
        assertThat(series.partial).isTrue()
        assertThat(series.months).hasSize(12)

        val listed = service.list().first { it.key == key }
        assertThat(listed.hasData).isTrue()
        assertThat(listed.availableFrom).isEqualTo(LocalDate.parse("2025-12-31"))
        assertThat(listed.availableTo).isEqualTo(LocalDate.parse("2026-01-31"))
    }

    @Test
    fun `a benchmark with no stored prices is all gaps and is flagged as having no data`() {
        val series = service.monthlyReturns(2026).first { it.key == key }

        assertThat(series.months).allSatisfy { assertThat(it.ret).isNull() }
        assertThat(series.partial).isTrue()
        assertThat(service.list().first { it.key == key }.hasData).isFalse()
    }

    @Test
    fun `daily closes carry the last observation across a closed market`() {
        seed("2026-03-06", "100") // Friday
        seed("2026-03-09", "110") // Monday

        val points = service.dailyCloses(LocalDate.parse("2026-03-06"), LocalDate.parse("2026-03-09"), listOf(key))
            .single().points.associate { it.date.toString() to it.close }

        assertThat(points["2026-03-06"]).isEqualByComparingTo("100")
        assertThat(points["2026-03-07"]).isEqualByComparingTo("100") // Saturday
        assertThat(points["2026-03-08"]).isEqualByComparingTo("100") // Sunday
        assertThat(points["2026-03-09"]).isEqualByComparingTo("110")
    }

    @Test
    fun `a window opening on a closed day carries the close from before it`() {
        seed("2026-03-06", "100")

        val points = service.dailyCloses(LocalDate.parse("2026-03-08"), LocalDate.parse("2026-03-08"), listOf(key))
            .single().points

        assertThat(points.single().close).isEqualByComparingTo("100")
    }

    @Test
    fun `days before the first close are gaps, not the first close carried backwards`() {
        seed("2026-03-06", "100")

        val points = service.dailyCloses(LocalDate.parse("2026-03-04"), LocalDate.parse("2026-03-06"), listOf(key))
            .single().points.associate { it.date.toString() to it.close }

        assertThat(points["2026-03-04"]).isNull()
        assertThat(points["2026-03-05"]).isNull()
        assertThat(points["2026-03-06"]).isEqualByComparingTo("100")
    }

    @Test
    fun `a discontinued series stops instead of drawing a flat line forever`() {
        seed("2026-03-06", "100")

        val points = service.dailyCloses(LocalDate.parse("2026-03-06"), LocalDate.parse("2026-03-31"), listOf(key))
            .single().points.associate { it.date.toString() to it.close }

        // Bridged: within the grace window a closed market is still a valid carry.
        assertThat(points["2026-03-11"]).isEqualByComparingTo("100")
        // Beyond it the feed has simply stopped, and a carried price would be invented.
        assertThat(points["2026-03-12"]).isNull()
        assertThat(points["2026-03-31"]).isNull()
    }

    @Test
    fun `dailyCloses covers every calendar day and filters to the requested keys`() {
        seed("2026-03-06", "100")

        val series = service.dailyCloses(LocalDate.parse("2026-03-06"), LocalDate.parse("2026-03-10"), listOf(key))

        assertThat(series).hasSize(1)
        assertThat(series.single().key).isEqualTo(key)
        assertThat(series.single().points).hasSize(5)
        assertThat(series.single().points.map { it.date }).isSorted()
        assertThat(service.dailyCloses(LocalDate.parse("2026-03-06"), LocalDate.parse("2026-03-10"), emptyList()))
            .hasSize(5) // no keys = every enabled benchmark
    }

    @Test
    fun `an inverted window yields nothing rather than a one-day series`() {
        seed("2026-03-06", "100")

        assertThat(service.dailyCloses(LocalDate.parse("2026-03-10"), LocalDate.parse("2026-03-06"), listOf(key)))
            .isEmpty()
    }

    @Test
    fun `every seeded benchmark is enabled, USD and exposed to the chart`() {
        assertThat(benchmarks.findAllByEnabledTrueOrderBySortOrderAsc().map { it.key })
            .containsExactly("bitcoin", "crypto_index", "msci_world", "sp500", "gold")
        assertThat(benchmarks.findAll()).allSatisfy { assertThat(it.currency).isEqualTo("USD") }
        assertThat(service.monthlyReturns(2026)).hasSize(5)
    }
}
