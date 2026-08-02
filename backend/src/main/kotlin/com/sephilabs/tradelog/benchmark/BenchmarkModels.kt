// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.benchmark

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** How a benchmark's closes are fetched: Yahoo's chart endpoint, or Binance USDT klines. */
enum class BenchmarkKind { equity, crypto }

/**
 * A reference index or asset whose buy-and-hold monthly returns can be overlaid on the Monthly ROI
 * chart. The row is the extension point: a new benchmark is one seeded record plus an i18n label.
 * Closes are always in USD ([currency] is asserted, never converted) — TradeLog's capital is
 * USDT-denominated, so an FX step would add currency drift to what should be the asset's own return.
 */
@Entity
@Table(name = "benchmarks")
class Benchmark(
    @Id
    @Column(name = "key", nullable = false, updatable = false, length = 32)
    var key: String,

    @Column(name = "source_provider", nullable = false, length = 24)
    var sourceProvider: String,

    @Column(name = "source_symbol", nullable = false, length = 120)
    var sourceSymbol: String,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = "USD",

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    var kind: BenchmarkKind,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
)

/**
 * One observed close per (benchmark, date), in USD. Non-trading days are never stored; reads
 * forward-fill from the last row <= date, which is what lets a Mon-Fri index be priced for a month
 * boundary that lands on a weekend.
 */
@Entity
@Table(name = "benchmark_prices")
class BenchmarkPrice(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "benchmark_key", nullable = false, length = 32, updatable = false)
    var benchmarkKey: String,

    @Column(name = "price_date", nullable = false, updatable = false)
    var priceDate: LocalDate,

    @Column(name = "close", nullable = false, precision = 28, scale = 12)
    var close: BigDecimal,

    @Column(name = "fetched_at", nullable = false)
    var fetchedAt: Instant = Instant.now(),
)

interface BenchmarkRepository : JpaRepository<Benchmark, String> {
    fun findAllByEnabledTrueOrderBySortOrderAsc(): List<Benchmark>
}

interface BenchmarkPriceRepository : JpaRepository<BenchmarkPrice, UUID> {

    fun findByBenchmarkKeyAndPriceDate(benchmarkKey: String, priceDate: LocalDate): BenchmarkPrice?

    /** Every close in the window, ascending — the read side loads one year at a time. */
    fun findAllByBenchmarkKeyAndPriceDateBetweenOrderByPriceDateAsc(
        benchmarkKey: String,
        from: LocalDate,
        to: LocalDate,
    ): List<BenchmarkPrice>

    /** The forward-fill lookup: the newest close at or before [priceDate]. */
    fun findFirstByBenchmarkKeyAndPriceDateLessThanEqualOrderByPriceDateDesc(
        benchmarkKey: String,
        priceDate: LocalDate,
    ): BenchmarkPrice?

    @Query("SELECT MIN(p.priceDate) FROM BenchmarkPrice p WHERE p.benchmarkKey = :key")
    fun findMinPriceDate(@Param("key") key: String): LocalDate?

    @Query("SELECT MAX(p.priceDate) FROM BenchmarkPrice p WHERE p.benchmarkKey = :key")
    fun findMaxPriceDate(@Param("key") key: String): LocalDate?
}
