// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.auth

import com.sephilabs.tradelog.config.AppProperties
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Per-user throttle for file imports (Quantfury PDF upload). */
@Component
class ImportRateLimiter(private val props: AppProperties) {

    private val buckets: MutableMap<String, Bucket> = ConcurrentHashMap()

    fun tryAcquire(key: String): Boolean =
        buckets.computeIfAbsent(key) { build() }.tryConsume(1)

    private fun build(): Bucket {
        val perHour = Bandwidth.builder()
            .capacity(props.security.importRate.perHour)
            .refillIntervally(props.security.importRate.perHour, Duration.ofHours(1))
            .build()
        return Bucket.builder().addLimit(perHour).build()
    }
}
