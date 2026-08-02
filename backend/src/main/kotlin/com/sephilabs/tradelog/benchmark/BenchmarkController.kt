// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.benchmark

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * Benchmark reference data. Deliberately NOT profile-scoped: a benchmark's price history is market
 * data, identical for every profile and every user, so scoping it would only imply an ownership
 * that does not exist. Still authenticated, like every other API route.
 */
@RestController
@RequestMapping("/api/benchmarks")
class BenchmarkController(private val service: BenchmarkService) {

    @GetMapping
    fun list(): List<BenchmarkDto> = service.list()

    /** Buy-and-hold monthly returns for the twelve months of [year], one series per benchmark. */
    @GetMapping("/monthly")
    fun monthly(@RequestParam year: Int): List<BenchmarkMonthlySeriesDto> = service.monthlyReturns(year)

    /**
     * Daily closes over a window, for the [keys] requested (all benchmarks when omitted). Callers
     * pass only what the user has switched on, so the default view fetches nothing.
     */
    @GetMapping("/daily")
    fun daily(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(required = false) keys: List<String>?,
    ): List<BenchmarkDailySeriesDto> = service.dailyCloses(from, to, keys.orEmpty())
}
