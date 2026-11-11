// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import com.sephilabs.tradelog.config.AppProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class AiProviderSettingsTest {

    private fun resolve(apiKey: String = "", baseUrl: String = DEFAULT_BASE, model: String = DEFAULT_MODEL) =
        AiProviderSettings.resolve(AppProperties.Ai(baseUrl = baseUrl, apiKey = apiKey, model = model))

    @Test
    fun `blank key with untouched defaults means absent`() {
        assertTrue(resolve() is AiProviderSettings.Absent)
    }

    @Test
    fun `changed url or model without a key is partial and names the missing variable`() {
        val partial = resolve(baseUrl = "https://other.example/v1") as AiProviderSettings.PartiallyConfigured
        assertEquals(listOf("AI_API_KEY"), partial.missing)
        assertEquals(listOf("AI_API_KEY"), (resolve(model = "other-model") as AiProviderSettings.PartiallyConfigured).missing)
    }

    @Test
    fun `key without a usable url or model is partial`() {
        assertEquals(listOf("AI_BASE_URL"), (resolve(apiKey = "k", baseUrl = "not-a-url") as AiProviderSettings.PartiallyConfigured).missing)
        assertEquals(listOf("AI_MODEL"), (resolve(apiKey = "k", model = "") as AiProviderSettings.PartiallyConfigured).missing)
    }

    @Test
    fun `key with usable url and model is configured`() {
        val configured = resolve(apiKey = " k ", baseUrl = "https://api.example.com/v1/") as AiProviderSettings.Configured
        assertEquals("k", configured.apiKey)
        assertEquals("api.example.com", configured.host)
        assertEquals(DEFAULT_MODEL, configured.model)
    }

    private companion object {
        const val DEFAULT_BASE = "https://opencode.ai/zen/go/v1"
        const val DEFAULT_MODEL = "deepseek-v4.1-flash"
    }
}

class GroundingVerifierTest {

    private val mapper = JsonMapper.builder().build()
    private val payload = mapper.readTree(
        """
        {"scope":{"exchange":"Bybit"},
         "summary":{"n":12,"totalPnl":123.45},
         "byHour":{"buckets":[{"hour":14,"n":11,"winRatePct":63.6}]}}
        """.trimIndent(),
    )

    private fun citation(path: String, value: Any): String =
        """{"path":"$path","value":$value}"""

    private fun grounded(path: String, value: Any): Boolean =
        GroundingVerifier.check(payload, mapper.readTree(citation(path, value))).grounded

    @Test
    fun `exact numbers and rounded display values match`() {
        assertTrue(grounded("summary.totalPnl", 123.45))
        assertTrue(grounded("summary.totalPnl", 123.46)) // within display tolerance
        assertTrue(grounded("summary.n", 12))
    }

    @Test
    fun `wrong values do not match`() {
        assertFalse(grounded("summary.totalPnl", 999.0))
        assertFalse(grounded("summary.n", 11))
    }

    @Test
    fun `array buckets resolve with dotted and bracket paths`() {
        assertTrue(grounded("byHour.buckets.0.winRatePct", 63.6))
        assertTrue(grounded("byHour.buckets[0].n", 11))
    }

    @Test
    fun `strings match case-sensitively`() {
        assertTrue(grounded("scope.exchange", "\"Bybit\""))
        assertFalse(grounded("scope.exchange", "\"bybit\""))
    }

    @Test
    fun `missing paths and malformed citations are ungrounded`() {
        assertFalse(grounded("summary.madeUp", 1))
        assertFalse(grounded("summary.totalPnl.extra", 1))
        val malformed = mapper.readTree("""{"path":"","value":1}""")
        assertFalse(GroundingVerifier.check(payload, malformed).grounded)
    }

    @Test
    fun `verify walks observations and insufficientSample in order`() {
        val answer = mapper.readTree(
            """
            {"observations":[
               {"claim":"a","citations":[${citation("summary.n", 12)},${citation("summary.madeUp", 1)}],"sampleSize":12,"confidence":"high"}],
             "insufficientSample":[
               {"topic":"t","reason":"r","citations":[${citation("byHour.buckets.0.n", 11)}]}]}
            """.trimIndent(),
        )
        val result = GroundingVerifier.verify(payload, answer)
        assertEquals(3, result.total)
        assertEquals(1, result.ungrounded)
        assertEquals(listOf("summary.n", "summary.madeUp", "byHour.buckets.0.n"), result.checks.map { it.path })
    }
}
