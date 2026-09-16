// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.passwordreset

import com.sephilabs.tradelog.config.AppProperties
import com.sephilabs.tradelog.identity.auth.EvictingBucketStore
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration

/** Both buckets are consumed before any user lookup and keyed on requester-supplied values (source
 *  IP, normalized target address), so a 429 says nothing about whether an account exists. */
@Component
class PasswordResetRateLimiter(private val props: AppProperties) {

    private val byIp = EvictingBucketStore<String>(retention = Duration.ofHours(1)) {
        hourly(props.passwordReset.perHourPerIp)
    }
    private val byEmail = EvictingBucketStore<String>(retention = Duration.ofHours(1)) {
        hourly(props.passwordReset.perHourPerEmail)
    }

    fun tryAcquireIp(ip: String): Boolean = byIp.tryAcquire(ip)

    fun tryAcquireEmail(normalizedEmail: String): Boolean = byEmail.tryAcquire(normalizedEmail)

    private fun hourly(capacity: Long): Bucket = Bucket.builder()
        .addLimit(Bandwidth.builder().capacity(capacity).refillIntervally(capacity, Duration.ofHours(1)).build())
        .build()
}
