// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.benchmark

import com.sephilabs.tradelog.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.concurrent.Executor

/**
 * Daily top-up of the benchmark price history. Once a night is enough: the series are daily closes,
 * and the gap-fill only asks each provider for the days it is actually missing.
 */
@Component
class BenchmarkRefreshJob(
    private val refresh: BenchmarkRefreshService,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${app.benchmark.schedule.cron:0 30 3 * * *}")
    fun run() {
        if (!props.benchmark.schedule.enabled) return
        runCatching { refresh.refresh(LocalDate.now()) }
            .onFailure { log.warn("Benchmark refresh sweep failed", it) }
    }
}

/**
 * On first start (or after a new benchmark is seeded), populates the price history so the Monthly
 * ROI overlay has something to draw without waiting for the nightly job. Runs only when an enabled
 * benchmark has nothing stored, and off the startup thread on the bounded sync executor — a normal
 * restart therefore makes no provider calls and never delays readiness.
 */
@Component
class BenchmarkBackfillRunner(
    private val benchmarks: BenchmarkRepository,
    private val prices: BenchmarkPriceRepository,
    private val refresh: BenchmarkRefreshService,
    private val props: AppProperties,
    @Qualifier("syncExecutor") private val executor: Executor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!props.benchmark.backfillOnStart) return
        val needsBootstrap = benchmarks.findAllByEnabledTrueOrderBySortOrderAsc()
            .any { prices.findMaxPriceDate(it.key) == null }
        if (!needsBootstrap) return
        log.info("Bootstrapping benchmark price history")
        executor.execute {
            runCatching { refresh.refresh(LocalDate.now()) }
                .onFailure { log.error("Benchmark bootstrap backfill failed", it) }
        }
    }
}
