// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.benchmark

import com.sephilabs.tradelog.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ContextConfiguration
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Exercises the refresh loop against a stub provider — no test reaches the network. What matters
 * here is that a re-run is a genuine no-op and that a provider serving a partial answer is repaired
 * by the next run, because both were observed against the real Yahoo endpoint.
 */
@ContextConfiguration(classes = [BenchmarkRefreshIntegrationTest.StubSourceConfig::class])
class BenchmarkRefreshIntegrationTest @Autowired constructor(
    private val refresh: BenchmarkRefreshService,
    private val prices: BenchmarkPriceRepository,
    private val stub: StubBenchmarkSource,
) : IntegrationTestBase() {

    private val today = LocalDate.parse("2026-03-10")

    @BeforeEach
    fun reset() {
        prices.deleteAll()
        stub.closes = emptyList()
        stub.failFor = null
    }

    private fun storedFor(key: String) = prices.findAllByBenchmarkKeyAndPriceDateBetweenOrderByPriceDateAsc(
        key, LocalDate.parse("2000-01-01"), today,
    )

    @Test
    fun `a second run writes nothing when the provider repeats itself`() {
        // Yahoo widens 32-bit quotes to double, so a close arrives with a tail the NUMERIC(28,12)
        // column cannot hold. Unless it is canonicalised first, every row differs from its own
        // stored form and the nightly job rewrites the whole window forever.
        stub.closes = listOf(
            DailyClose(LocalDate.parse("2026-03-02"), BigDecimal("189.44000244140625")),
            DailyClose(LocalDate.parse("2026-03-03"), BigDecimal("190.5")),
        )

        refresh.refresh(today)
        val afterFirst = storedFor("gold").map { it.id to it.fetchedAt }

        refresh.refresh(today)
        val afterSecond = storedFor("gold").map { it.id to it.fetchedAt }

        assertThat(afterFirst).hasSize(2)
        // Same rows, same fetchedAt: nothing was touched the second time round.
        assertThat(afterSecond).isEqualTo(afterFirst)
    }

    @Test
    fun `the stored close is readable back at the column's precision`() {
        stub.closes = listOf(DailyClose(LocalDate.parse("2026-03-02"), BigDecimal("189.44000244140625")))

        refresh.refresh(today)

        assertThat(storedFor("gold").single().close).isEqualByComparingTo("189.440002441406")
    }

    @Test
    fun `a truncated answer is completed by the next run`() {
        stub.closes = listOf(DailyClose(LocalDate.parse("2026-03-02"), BigDecimal("100")))
        refresh.refresh(today)
        assertThat(storedFor("gold")).hasSize(1)

        // The provider recovers and serves the full window; the run must pick up what it missed
        // rather than only looking past the last date it already had.
        stub.closes = listOf(
            DailyClose(LocalDate.parse("2026-03-02"), BigDecimal("100")),
            DailyClose(LocalDate.parse("2026-03-03"), BigDecimal("101")),
            DailyClose(LocalDate.parse("2026-03-04"), BigDecimal("102")),
        )
        refresh.refresh(today)

        assertThat(storedFor("gold").map { it.priceDate.toString() })
            .containsExactly("2026-03-02", "2026-03-03", "2026-03-04")
    }

    @Test
    fun `a restated close overwrites the stored one`() {
        stub.closes = listOf(DailyClose(LocalDate.parse("2026-03-02"), BigDecimal("100")))
        refresh.refresh(today)

        stub.closes = listOf(DailyClose(LocalDate.parse("2026-03-02"), BigDecimal("103.25")))
        refresh.refresh(today)

        assertThat(storedFor("gold").single().close).isEqualByComparingTo("103.25")
    }

    @Test
    fun `one failing benchmark does not stop the others`() {
        stub.failFor = "gold"
        stub.closes = listOf(DailyClose(LocalDate.parse("2026-03-02"), BigDecimal("100")))

        refresh.refresh(today)

        assertThat(storedFor("gold")).isEmpty()
        assertThat(storedFor("sp500")).hasSize(1)
        assertThat(storedFor("bitcoin")).hasSize(1)
    }

    /** Returns whatever the test sets, for every benchmark. */
    class StubBenchmarkSource : BenchmarkSource {
        var closes: List<DailyClose> = emptyList()
        var failFor: String? = null

        override fun dailyCloses(benchmark: Benchmark, from: LocalDate, to: LocalDate): List<DailyClose> {
            if (benchmark.key == failFor) throw BenchmarkSourceException("stub failure for ${benchmark.key}")
            return closes.filter { it.date >= from && it.date <= to }
        }
    }

    @TestConfiguration
    class StubSourceConfig {
        @Bean
        @Primary
        fun stubBenchmarkSource() = StubBenchmarkSource()
    }
}
