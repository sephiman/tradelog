// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.auth

import com.sephilabs.tradelog.config.AppProperties
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Component
class LoginRateLimiter(private val props: AppProperties) {

    private class Entry(val bucket: Bucket) {
        @Volatile
        var lastAccess: Long = System.nanoTime()
    }

    private val buckets = ConcurrentHashMap<String, Entry>()

    fun tryAcquire(key: String): Boolean {
        val entry = buckets.computeIfAbsent(key) { Entry(build()) }
        entry.lastAccess = System.nanoTime()
        return entry.bucket.tryConsume(1)
    }

    fun reset(key: String) {
        buckets.remove(key)
    }

    /**
     * Bounds the per-IP map by evicting entries idle longer than the longest refill window: by then
     * every bandwidth has fully refilled, so the entry is indistinguishable from a fresh bucket and
     * dropping it cannot reset anyone's limit. (A size-capped LRU would be unsafe here — an attacker
     * could flood junk keys to flush their own exhausted bucket and restart brute-forcing.) Memory
     * stays proportional to the distinct client IPs seen per hour.
     */
    @Scheduled(fixedDelay = EVICTION_SWEEP_MS)
    fun evictIdle() {
        val cutoff = System.nanoTime() - IDLE_EVICTION.toNanos()
        buckets.entries.removeIf { it.value.lastAccess < cutoff }
    }

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

    private companion object {
        /** Must be at least the longest bandwidth window (1h), so eviction is always lossless. */
        val IDLE_EVICTION: Duration = Duration.ofHours(1)
        const val EVICTION_SWEEP_MS = 10L * 60L * 1000L
    }
}
