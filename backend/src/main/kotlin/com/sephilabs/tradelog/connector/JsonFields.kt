// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.connector

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime

// Defensive readers for exchange payloads: candidate keys survive a field rename, and everything is
// re-parsed from text because exchanges send numbers as JSON strings as often as as numbers.

private fun JsonNode.valueAt(keys: List<String>): JsonNode? =
    keys.firstNotNullOfOrNull { k -> path(k).takeIf { it.isValueNode && !it.isNull } }

fun JsonNode.text(keys: List<String>): String? =
    valueAt(keys)?.asText()?.takeIf { it.isNotBlank() }

fun JsonNode.text(vararg keys: String): String? = text(keys.asList())

fun JsonNode.dec(keys: List<String>): BigDecimal? = text(keys)?.toBigDecimalOrNull()

fun JsonNode.dec(vararg keys: String): BigDecimal? = dec(keys.asList())

fun JsonNode.long(keys: List<String>): Long? = text(keys)?.toLongOrNull()

fun JsonNode.long(vararg keys: String): Long? = long(keys.asList())

fun JsonNode.int(keys: List<String>): Int? = text(keys)?.toIntOrNull()

fun JsonNode.int(vararg keys: String): Int? = int(keys.asList())

/**
 * Epoch seconds or milliseconds (whole or fractional), numeric or string, or ISO-8601. Null when
 * nothing parses — callers skip the row rather than dating a real trade to 1970.
 */
fun JsonNode.instant(keys: List<String>): Instant? {
    for (k in keys) {
        val node = path(k)
        if (node.isMissingNode || node.isNull) continue
        val s = node.asText().trim()
        if (s.isEmpty()) continue
        s.toLongOrNull()?.let { return epochToInstant(it) }
        s.toBigDecimalOrNull()?.let { return epochToInstant(it) }
        runCatching { return Instant.parse(s) }
        runCatching { return OffsetDateTime.parse(s).toInstant() }
    }
    return null
}

fun JsonNode.instant(vararg keys: String): Instant? = instant(keys.asList())

/** Treat values below 10^12 as epoch seconds, otherwise milliseconds (10^12 ms ≈ year 2001). */
fun epochToInstant(v: Long): Instant =
    if (v < MILLIS_THRESHOLD) Instant.ofEpochSecond(v) else Instant.ofEpochMilli(v)

/** As [epochToInstant], keeping the sub-second part of a fractional epoch value. */
fun epochToInstant(v: BigDecimal): Instant {
    val millis = if (v.abs() < BigDecimal(MILLIS_THRESHOLD)) v.movePointRight(3) else v
    return Instant.ofEpochMilli(millis.setScale(0, java.math.RoundingMode.FLOOR).toLong())
}

private const val MILLIS_THRESHOLD = 1_000_000_000_000L

/** The first of [paths] holding an array; paths are dot-separated. Empty = end of the data. */
fun JsonNode.rows(paths: List<String>): List<JsonNode> {
    for (p in paths) {
        var node: JsonNode = this
        for (segment in p.split('.')) {
            if (segment.isEmpty()) continue
            node = node.path(segment)
        }
        if (node.isArray) return node.toList()
    }
    return emptyList()
}

fun JsonNode.rows(vararg paths: String): List<JsonNode> = rows(paths.asList())

/**
 * Values of the first of [paths] holding an OBJECT keyed by id, as Kraken spot returns its trades.
 * Kept separate from [rows] so that helper's meaning does not shift for the other ten connectors.
 */
fun JsonNode.objectValues(paths: List<String>): List<JsonNode> {
    for (p in paths) {
        var node: JsonNode = this
        for (segment in p.split('.')) {
            if (segment.isEmpty()) continue
            node = node.path(segment)
        }
        if (node.isObject) return node.properties().map { it.value }
    }
    return emptyList()
}
