// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.benchmark

import com.fasterxml.jackson.annotation.JsonFormat
import java.math.BigDecimal
import java.time.LocalDate

/** One selectable benchmark plus the extent of its stored data (for the chart legend). */
data class BenchmarkDto(
    val key: String,
    val hasData: Boolean,
    val availableFrom: LocalDate?,
    val availableTo: LocalDate?,
)

/** One month's buy-and-hold return of a benchmark. */
data class BenchmarkMonthlyReturnDto(
    /** 1..12. */
    val month: Int,
    /**
     * Fraction, e.g. 0.052 = +5.2% — the same shape as [com.sephilabs.tradelog.capital.MonthlyRoiItemDto.roi],
     * so the chart scales both series identically. Null is a data gap (no prices for that month),
     * never a 0% month.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val ret: BigDecimal?,
)

/** A benchmark's twelve monthly returns for one calendar year; [partial] flags any gap among them. */
data class BenchmarkMonthlySeriesDto(
    val key: String,
    val months: List<BenchmarkMonthlyReturnDto>,
    val partial: Boolean,
)

/** One calendar day's USD close of a benchmark. */
data class BenchmarkDailyCloseDto(
    val date: LocalDate,
    /**
     * Carried forward from the last observed close, so a market-closed day still has a value.
     * Null where the benchmark genuinely has nothing: before its first close, or far enough past
     * its last one that carrying forward would be inventing a price rather than bridging a weekend.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING) val close: BigDecimal?,
)

/**
 * A benchmark's daily closes over a requested window, one entry per calendar day so a caller can
 * look up any date directly. Closes are levels, not returns: the caller divides two of them to get
 * the growth between any pair of dates it chooses. [partial] flags any gap in the window.
 */
data class BenchmarkDailySeriesDto(
    val key: String,
    val points: List<BenchmarkDailyCloseDto>,
    val partial: Boolean,
)
