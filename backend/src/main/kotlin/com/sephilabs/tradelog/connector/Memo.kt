// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import java.time.Duration
import java.time.Instant

/** A value re-read from the venue at most once per [ttl]. */
class Memo<T>(private val ttl: Duration, private val load: () -> T) {

    private var value: T? = null
    private var loadedAt: Instant? = null

    @Synchronized
    fun get(): T {
        val cached = value
        val at = loadedAt
        if (cached != null && at != null && Duration.between(at, Instant.now()) < ttl) return cached
        val fresh = load()
        value = fresh
        loadedAt = Instant.now()
        return fresh
    }
}
