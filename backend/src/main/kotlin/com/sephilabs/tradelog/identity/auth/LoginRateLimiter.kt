// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.auth

import com.sephilabs.tradelog.config.AppProperties
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class LoginRateLimiter(private val props: AppProperties) {

    // Retention is the longest bandwidth window (1h), which is what makes eviction lossless.
    private val buckets = EvictingBucketStore<String>(retention = Duration.ofHours(1)) { build() }

    fun tryAcquire(key: String): Boolean = buckets.tryAcquire(key)

    fun reset(key: String) = buckets.remove(key)

    private fun build(): Bucket {
        val perMinute = Bandwidth.builder()
            .capacity(props.security.loginRate.perMinute)
            .refillIntervally(props.security.loginRate.perMinute, Duration.ofMinutes(1))
            .build()
        val perHour = Bandwidth.builder()
            .capacity(props.security.loginRate.perHour)
            .refillIntervally(props.security.loginRate.perHour, Duration.ofHours(1))
            .build()
        return Bucket.builder().addLimit(perMinute).addLimit(perHour).build()
    }
}
