// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import com.sephilabs.tradelog.identity.auth.EvictingBucketStore
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/** An analysis costs provider tokens, so a runaway client cannot burn the operator's budget:
 *  [MAX_PER_HOUR] starts per user per hour. */
@Component
class AiAnalysisRateLimiter {

    // Retention is the longest bandwidth window (1h), which is what makes eviction lossless.
    private val buckets = EvictingBucketStore<UUID>(retention = Duration.ofHours(1)) { build() }

    fun tryAcquire(userId: UUID): Boolean = buckets.tryAcquire(userId)

    private fun build(): Bucket = Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(MAX_PER_HOUR)
                .refillIntervally(MAX_PER_HOUR, Duration.ofHours(1))
                .build()
        )
        .build()

    private companion object {
        const val MAX_PER_HOUR = 10L
    }
}
