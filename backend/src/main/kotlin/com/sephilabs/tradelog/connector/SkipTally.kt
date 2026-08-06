// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.Logger

/** A row a connector could not map, and why. Paired with [SkipTally] so nothing vanishes quietly. */
sealed interface Mapped<out T> {
    data class Ok<T>(val value: T) : Mapped<T>
    data class Skip(val reason: String) : Mapped<Nothing>
}

/** Counts the rows a connector had to drop and reports them at WARN, with one sample payload. */
class SkipTally {

    private val byReason = LinkedHashMap<String, Int>()
    private var firstSample: String? = null

    val total: Int get() = byReason.values.sum()

    fun record(reason: String, row: JsonNode) {
        byReason.merge(reason, 1, Int::plus)
        if (firstSample == null) firstSample = row.toString().take(SAMPLE_CHARS)
    }

    /** Fold [row] into [into] when it mapped, otherwise tally the reason. Returns true when kept. */
    fun <T> keep(row: JsonNode, mapped: Mapped<T>, into: MutableCollection<T>): Boolean =
        when (mapped) {
            is Mapped.Ok -> { into += mapped.value; true }
            is Mapped.Skip -> { record(mapped.reason, row); false }
        }

    fun report(log: Logger, venue: String, context: String, seen: Int) {
        if (byReason.isEmpty()) return
        log.warn(
            "{}: skipped {} of {} row(s) {} {} — first offender: {}",
            venue, total, seen, byReason, context, firstSample,
        )
    }

    private companion object {
        const val SAMPLE_CHARS = 400
    }
}
