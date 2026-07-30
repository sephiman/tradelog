// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.datasource

/**
 * The venue-name namespace. A position's `exchange` is free text (a Journal CSV row can name any dead
 * exchange), but it is also an identity: capital anchors, ROI and every per-exchange grouping key off
 * the exact string. Two spellings of one venue therefore split its capital and its history in two —
 * which is what happened when a CSV said "Bingx" while the BingX connector wrote "BingX".
 *
 */
object Venues {

    /** The canonical spelling of each venue tradelog connects to, keyed by its comparison form. */
    private val CONNECTOR_VENUES: Map<String, String> =
        SourceKind.entries.mapNotNull { it.venueLabel }.associateBy(::comparisonKey)

    /**
     * The canonical spelling of [raw]: the connector's own label when [raw] names a venue tradelog
     * connects to, otherwise the matching spelling already present in [known], otherwise the trimmed
     * input as the user wrote it. [known] is normally the profile's venues, so the first import that
     * names a venue fixes its spelling for the ones that follow.
     */
    fun canonical(raw: String, known: Collection<String> = emptyList()): String {
        val trimmed = raw.trim()
        val key = comparisonKey(trimmed)
        if (key.isEmpty()) return trimmed
        CONNECTOR_VENUES[key]?.let { return it }
        return known.firstOrNull { comparisonKey(it) == key } ?: trimmed
    }

    /** Case- and punctuation-insensitive, so "BingX", "bingx" and "bing-x" are one venue. */
    private fun comparisonKey(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }
}
