// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class AppMetrics(private val registry: MeterRegistry) {

    fun loginAttempt(outcome: String) {
        Counter.builder("tl_login_attempts_total")
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }

    fun registration(mode: String, outcome: String) {
        Counter.builder("tl_registrations_total")
            .tag("mode", mode)
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }

    fun syncRun(source: String, trigger: String, outcome: String) {
        Counter.builder("tl_sync_runs_total")
            .tag("source", source)
            .tag("trigger", trigger)
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }

    /** One nightly gap-fill attempt per benchmark; a failure means that series stayed stale. */
    fun benchmarkRefresh(benchmark: String, outcome: String) {
        Counter.builder("tl_benchmark_refresh_total")
            .tag("benchmark", benchmark)
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }

    fun positionsUpserted(source: String, count: Int) {
        if (count <= 0) return
        Counter.builder("tl_positions_upserted_total")
            .tag("source", source)
            .register(registry)
            .increment(count.toDouble())
    }
}
