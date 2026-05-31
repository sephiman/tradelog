// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.datasource.SourceKind
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Throttles outbound sync calls per exchange (per [SourceKind]), so multiple sub-account data
 * sources on the same exchange share one quota and a backlog can't hammer the venue.
 */
@Component
class ExchangeRateLimiter(private val props: AppProperties) {

    private val buckets: MutableMap<SourceKind, Bucket> = ConcurrentHashMap()

    fun tryAcquire(kind: SourceKind): Boolean =
        buckets.computeIfAbsent(kind) { build(perMinute(it)) }.tryConsume(1)

    private fun perMinute(kind: SourceKind): Long = when (kind) {
        SourceKind.BITUNIX -> props.sync.rate.bitunixPerMinute
        SourceKind.BINGX -> props.sync.rate.bingxPerMinute
        SourceKind.QUANTFURY, SourceKind.JOURNAL_CSV -> Long.MAX_VALUE
    }

    private fun build(perMinute: Long): Bucket {
        val limit = Bandwidth.builder()
            .capacity(perMinute)
            .refillIntervally(perMinute, Duration.ofMinutes(1))
            .build()
        return Bucket.builder().addLimit(limit).build()
    }
}
