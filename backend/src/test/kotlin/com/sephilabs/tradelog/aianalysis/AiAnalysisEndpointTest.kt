// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import com.sephilabs.tradelog.identity.AuthHttpTestBase
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** The provider conversation runs synchronously in tests; the stub answers a fixed grounded/ungrounded mix. */
@TestConfiguration(proxyBeanMethods = false)
class AiTestConfig {

    var lastPrompt: ChatPrompt? = null

    @Bean
    @Primary
    fun stubChatClient(): AiChatClient =
        object : AiChatClient {
            override fun complete(prompt: ChatPrompt): ChatAnswer {
                lastPrompt = prompt
                val body = if (prompt.system.contains("follow-up")) {
                    """{"answer":"El peor resultado en BTC/USDT fue de -45.0.","citations":[{"path":"trades.0.netPnl","value":-45.0}]}"""
                } else {
                    """
                    {"summary":"Operador de sesgo intradiario con buen control de riesgo general.",
                     "evolution":"Mejora notable en el control de pérdidas respecto a periodos previos.",
                     "strengths":[
                       {"title":"Expectativa positiva en largos",
                        "detail":"Los largos sostienen el resultado neto del periodo.",
                        "citations":[{"path":"summary.totalPnl","value":123.45}]}],
                     "improvements":[
                       {"title":"Reducir sobreoperativa nocturna",
                        "detail":"Concentrar las entradas en las sesiones de mayor liquidez.",
                        "severity":"critical",
                        "citations":[{"path":"summary.totalPnl","value":123.45}]}],
                     "tradingRules":[
                       {"rule":"Máximo 3 operaciones por día",
                        "trigger":"Tras cerrar la tercera operación diaria",
                        "expectedImpact":"Evita la caída por fatiga.",
                        "citations":[{"path":"trades.0.netPnl","value":-45.0}]}],
                     "observations":[
                       {"claim":"Las pérdidas medias superan el límite por operación.",
                        "citations":[{"path":"summary.totalPnl","value":123.45},{"path":"summary.madeUp.path","value":1}],
                        "sampleSize":12,
                        "confidence":"alta"}],
                     "insufficientSample":[
                       {"topic":"Horas del día","reason":"Se descartaron tramos con menos de 10 operaciones.",
                        "citations":[{"path":"summary.n","value":12}]}]}
                """.trimIndent()
                }
                return ChatAnswer(
                    content = body,
                    inputTokens = 111,
                    outputTokens = 22,
                    reasoningTokens = 3,
                    latencyMs = 456,
                    attempts = 1,
                )
            }
        }
}

/** Endpoint behaviour with a configured provider: features, settings gate, run lifecycle and grounding. */
@Import(AiTestConfig::class)
@TestPropertySource(
    properties = [
        "app.ai.api-key=test-key",
        "app.ai.base-url=http://localhost:9/v1",
        "app.ai.model=test-model",
    ],
)
class AiAnalysisEndpointTest : AuthHttpTestBase() {

    @org.springframework.beans.factory.annotation.Autowired
    lateinit var aiTestConfig: AiTestConfig

    @Test
    fun `features report the provider and the setting can be turned on`() {
        val session = sessionOf(register(newEmail("ai-on")))
        mockMvc.perform(get("/api/features").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ai").value(true))
        mockMvc.perform(
            put("/api/ai/settings").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":true}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.model").value("test-model"))
            .andExpect(jsonPath("$.providerHost").value("localhost"))
    }

    @Test
    fun `a full run persists history with grounding counts and resolved tag names`() {
        val session = sessionOf(register(newEmail("ai-run")))
        enableAi(session)
        val profileId = createProfile(session)
        val (groupId, tagId) = firstGroupAndTagId(session)

        val payload = """
            {
              "scope": {"periodPreset":"all","rangeFrom":null,"rangeTo":null,"exchange":"ALL","origen":null,"timeZone":"UTC"},
              "summary": {"n":12,"totalPnl":123.45},
              "trades": [{"seq":1,"pair":"BTC/USDT","netPnl":-45.0}],
              "tagMap": {"Tag A": "$tagId", "Group A": "$groupId"}
            }
        """.trimIndent()

        val started = mockMvc.perform(
            post("/api/profiles/$profileId/ai-analysis").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"payload": $payload}"""),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.scope.sampleSize").value(12))
            .andReturn()

        mockMvc.perform(get("/api/profiles/$profileId/ai-analysis").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].status").value("COMPLETED"))
            .andExpect(jsonPath("$[0].model").value("test-model"))
            .andExpect(jsonPath("$[0].promptVersion").value(TradingStylePrompt.VERSION))
            .andExpect(jsonPath("$[0].summary").value("Operador de sesgo intradiario con buen control de riesgo general."))
            .andExpect(jsonPath("$[0].evolution").value("Mejora notable en el control de pérdidas respecto a periodos previos."))
            .andExpect(jsonPath("$[0].strengths.length()").value(1))
            .andExpect(jsonPath("$[0].strengths[0].title").value("Expectativa positiva en largos"))
            .andExpect(jsonPath("$[0].strengths[0].citations[0].grounded").value(true))
            .andExpect(jsonPath("$[0].improvements.length()").value(1))
            .andExpect(jsonPath("$[0].improvements[0].title").value("Reducir sobreoperativa nocturna"))
            .andExpect(jsonPath("$[0].improvements[0].severity").value("critical"))
            .andExpect(jsonPath("$[0].improvements[0].citations[0].grounded").value(true))
            .andExpect(jsonPath("$[0].tradingRules.length()").value(1))
            .andExpect(jsonPath("$[0].tradingRules[0].rule").value("Máximo 3 operaciones por día"))
            .andExpect(jsonPath("$[0].tradingRules[0].citations[0].grounded").value(true))
            .andExpect(jsonPath("$[0].tradingRules[0].citations[0].context").value("#1 BTC/USDT"))
            .andExpect(jsonPath("$[0].observations.length()").value(1))
            .andExpect(jsonPath("$[0].observations[0].citations.length()").value(2))
            .andExpect(jsonPath("$[0].observations[0].citations[0].grounded").value(true))
            .andExpect(jsonPath("$[0].observations[0].citations[1].grounded").value(false))
            .andExpect(jsonPath("$[0].insufficientSample[0].citations[0].grounded").value(true))
            .andExpect(jsonPath("$[0].citationsTotal").value(6))
            .andExpect(jsonPath("$[0].citationsUngrounded").value(1))
            .andExpect(jsonPath("$[0].inputTokens").value(111))
            .andExpect(jsonPath("$[0].outputTokens").value(22))
            .andExpect(jsonPath("$[0].tagNames['Tag A']").value("Discrecional"))
            .andExpect(jsonPath("$[0].tagNames['Group A']").value("Origen"))
            .andExpect(jsonPath("$[0].scope.periodPreset").value("all"))
            .andExpect(jsonPath("$[0].scope.timeZone").value("UTC"))

        val runId = com.jayway.jsonpath.JsonPath.read<String>(started.response.contentAsString, "$.id")

        // Follow-up Q&A validates input, grounds citations, persists history, and resolves citation context.
        mockMvc.perform(
            post("/api/profiles/$profileId/ai-analysis/$runId/followups").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"   "}"""),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("AI_ANALYSIS_QUESTION_INVALID"))

        mockMvc.perform(
            post("/api/profiles/$profileId/ai-analysis/$runId/followups").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"¿Qué pasó con BTC/USDT?"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.question").value("¿Qué pasó con BTC/USDT?"))
            .andExpect(jsonPath("$.answer").value("El peor resultado en BTC/USDT fue de -45.0."))
            .andExpect(jsonPath("$.citations[0].grounded").value(true))
            .andExpect(jsonPath("$.citations[0].context").value("#1 BTC/USDT"))
            .andExpect(jsonPath("$.citationsTotal").value(1))
            .andExpect(jsonPath("$.citationsUngrounded").value(0))

        mockMvc.perform(get("/api/profiles/$profileId/ai-analysis").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].followups.length()").value(1))
            .andExpect(jsonPath("$[0].followups[0].question").value("¿Qué pasó con BTC/USDT?"))

        // Prior run prefers differing sampleSize over differing rangeTo with same sampleSize.
        val month19 = """{"scope":{"periodPreset":"month","rangeFrom":"2026-11-01","rangeTo":"2026-11-15","exchange":"ALL","origen":null,"timeZone":"UTC"},"summary":{"n":19,"totalPnl":-100.0}}"""
        mockMvc.perform(
            post("/api/profiles/$profileId/ai-analysis").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"payload": $month19}"""),
        ).andExpect(status().isAccepted)

        val month25Full = """{"scope":{"periodPreset":"month","rangeFrom":"2026-11-01","rangeTo":"2026-11-30","exchange":"ALL","origen":null,"timeZone":"UTC"},"summary":{"n":25,"totalPnl":10.0}}"""
        mockMvc.perform(
            post("/api/profiles/$profileId/ai-analysis").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"payload": $month25Full}"""),
        ).andExpect(status().isAccepted)

        val month25Clamped = """{"scope":{"periodPreset":"month","rangeFrom":"2026-11-01","rangeTo":"2026-11-15","exchange":"ALL","origen":null,"timeZone":"UTC"},"summary":{"n":25,"totalPnl":10.0}}"""
        mockMvc.perform(
            post("/api/profiles/$profileId/ai-analysis").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"payload": $month25Clamped}"""),
        ).andExpect(status().isAccepted)
        val userPrompt = aiTestConfig.lastPrompt?.user ?: ""
        org.junit.jupiter.api.Assertions.assertTrue(userPrompt.contains("n=19"))
        org.junit.jupiter.api.Assertions.assertTrue(userPrompt.contains("Prior improvements: Reducir sobreoperativa nocturna"))

        mockMvc.perform(
            delete("/api/profiles/$profileId/ai-analysis/$runId").cookie(session).with(csrf()),
        )
            .andExpect(status().isOk)
        mockMvc.perform(get("/api/profiles/$profileId/ai-analysis").cookie(session))
            .andExpect(jsonPath("$.length()").value(3))
    }

    @Test
    fun `payloads below the minimum sample are refused`() {
        val session = sessionOf(register(newEmail("ai-small")))
        enableAi(session)
        val profileId = createProfile(session)
        mockMvc.perform(
            post("/api/profiles/$profileId/ai-analysis").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"payload": {"scope":{"timeZone":"UTC","exchange":"ALL","periodPreset":"all"},"summary":{"n":5,"totalPnl":1.0}}}""",
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("AI_ANALYSIS_SAMPLE_TOO_SMALL"))
    }

    @Test
    fun `a user that never opted in gets the disabled conflict`() {
        val session = sessionOf(register(newEmail("ai-off")))
        val profileId = createProfile(session)
        mockMvc.perform(
            post("/api/profiles/$profileId/ai-analysis").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"payload": {"scope":{"timeZone":"UTC","exchange":"ALL","periodPreset":"all"},"summary":{"n":12}}}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("AI_FEATURES_DISABLED"))
    }

    private fun enableAi(session: Cookie) {
        mockMvc.perform(
            put("/api/ai/settings").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":true}"""),
        ).andExpect(status().isOk)
    }

    private fun createProfile(session: Cookie): String {
        val result = mockMvc.perform(
            post("/api/profiles").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"kind":"PERSONAL","name":"Main"}"""),
        ).andExpect(status().isCreated).andReturn()
        return com.jayway.jsonpath.JsonPath.read<String>(result.response.contentAsString, "$.id")
    }

    /** GET /taxonomy/groups seeds the default taxonomy; returns first group id and its first tag id. */
    private fun firstGroupAndTagId(session: Cookie): Pair<String, String> {
        val result = mockMvc.perform(get("/api/taxonomy/groups").cookie(session))
            .andExpect(status().isOk)
            .andReturn()
        val body = result.response.contentAsString
        return com.jayway.jsonpath.JsonPath.read<String>(body, "$[0].id") to
            com.jayway.jsonpath.JsonPath.read<String>(body, "$[0].tags[0].id")
    }
}
