// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import tools.jackson.databind.JsonNode

/**
 * Verifies that every value the model cited exists in the payload with that value. A citation is an
 * object with a `path` (dot-separated, arrays indexed numerically or with brackets) and a `value`.
 * Numbers match within a small display-rounding tolerance; strings and booleans match exactly.
 */
object GroundingVerifier {

    data class CitationCheck(
        val path: String,
        val grounded: Boolean,
        val citedValue: JsonNode?,
        val actualValue: JsonNode?,
    )

    data class Result(val total: Int, val ungrounded: Int, val checks: List<CitationCheck>)

    /** Checks the citations of [answer]'s strengths, improvements, trading rules, observations and insufficient-sample items, in field order. */
    fun verify(payload: JsonNode, answer: JsonNode): Result {
        val checks = mutableListOf<CitationCheck>()
        answer.path("strengths").forEach { item -> item.path("citations").forEach { checks += check(payload, it) } }
        answer.path("improvements").forEach { item -> item.path("citations").forEach { checks += check(payload, it) } }
        answer.path("tradingRules").forEach { item -> item.path("citations").forEach { checks += check(payload, it) } }
        answer.path("observations").forEach { obs -> obs.path("citations").forEach { checks += check(payload, it) } }
        answer.path("insufficientSample").forEach { item -> item.path("citations").forEach { checks += check(payload, it) } }
        return Result(checks.size, checks.count { !it.grounded }, checks)
    }

    /** Checks a standalone array of citations (e.g. a follow-up Q&A answer). */
    fun verifyCitations(payload: JsonNode, citations: JsonNode): Result {
        val checks = if (citations.isArray) citations.toList().map { check(payload, it) } else emptyList()
        return Result(checks.size, checks.count { !it.grounded }, checks)
    }

    fun check(payload: JsonNode, citation: JsonNode): CitationCheck {
        val path = citation.path("path").asString(null)
        val cited = citation.path("value").takeIf { !it.isMissingNode }
        if (path.isNullOrBlank() || cited == null) {
            return CitationCheck(path ?: "", grounded = false, citedValue = cited, actualValue = null)
        }
        val actual = resolve(payload, path)
        return CitationCheck(path, matches(cited, actual), cited, actual)
    }

    /** Walks [path] against [payload]; null when any segment is missing (preserves explicit JSON nulls). */
    fun resolve(payload: JsonNode, path: String): JsonNode? {
        var node: JsonNode = payload
        for (rawSegment in path.replace("[", ".").replace("]", "").split('.')) {
            if (rawSegment.isEmpty()) continue
            node = if (node.isArray && rawSegment.toIntOrNull() != null) {
                node.path(rawSegment.toInt())
            } else {
                node.path(rawSegment)
            }
            if (node.isMissingNode) return null
        }
        return node
    }

    private fun matches(cited: JsonNode, actual: JsonNode?): Boolean {
        if (actual == null) return false
        return when {
            cited.isNull && actual.isNull -> true
            cited.isNumber && actual.isNumber ->
                Math.abs(cited.doubleValue() - actual.doubleValue()) <= tolerance(actual.doubleValue())
            cited.isBoolean && actual.isBoolean -> cited.booleanValue() == actual.booleanValue()
            cited.isString && actual.isString -> cited.asString().trim() == actual.asString().trim()
            cited.isArray && actual.isArray ->
                cited.size() == actual.size() && (0 until cited.size()).all { matches(cited[it], actual[it]) }
            cited.isObject && actual.isObject -> {
                val citedKeys = cited.properties().asSequence().map { it.key }.toSet()
                val actualKeys = actual.properties().asSequence().map { it.key }.toSet()
                citedKeys == actualKeys && citedKeys.all { matches(cited.path(it), actual.path(it)) }
            }
            else -> false
        }
    }

    /** Payload numbers are rounded to display precision, so the cited value may differ slightly. */
    private fun tolerance(actual: Double): Double = maxOf(0.011, Math.abs(actual) * 0.0005)
}
