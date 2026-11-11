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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** The provider conversation runs synchronously in tests; the stub answers a fixed grounded/ungrounded mix. */
@TestConfiguration(proxyBeanMethods = false)
class AiTestConfig {

    @Bean
    @Primary
    fun stubChatClient(): AiChatClient =
        object : AiChatClient {
            override fun complete(prompt: ChatPrompt): ChatAnswer = ChatAnswer(
                content = """
                    {"observations":[
                       {"claim":"Las pérdidas medias superan el límite por operación.",
                        "citations":[{"path":"summary.totalPnl","value":123.45},{"path":"summary.madeUp.path","value":1}],
                        "sampleSize":12,
                        "confidence":"alta"}],
                     "insufficientSample":[
                       {"topic":"Horas del día","reason":"Se descartaron tramos con menos de 10 operaciones.",
                        "citations":[{"path":"summary.n","value":12}]}]}
                """.trimIndent(),
                inputTokens = 111,
                outputTokens = 22,
                reasoningTokens = 3,
                latencyMs = 456,
                attempts = 1,
            )
        }
}

/** Endpoint behaviour with a configured provider: features, settings gate, run lifecycle and grounding. */
@Import(AiTestConfig::class)
class AiAnalysisEndpointTest : AuthHttpTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun aiProvider(registry: DynamicPropertyRegistry) {
            registry.add("app.ai.api-key") { "test-key" }
            registry.add("app.ai.base-url") { "http://localhost:9/v1" }
            registry.add("app.ai.model") { "test-model" }
        }
    }

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
        val tagId = firstTagId(session)

        val payload = """
            {
              "scope": {"periodPreset":"all","rangeFrom":null,"rangeTo":null,"exchange":"ALL","origen":null,"timeZone":"UTC"},
              "summary": {"n":12,"totalPnl":123.45},
              "tagMap": {"Tag A": "$tagId"}
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
            .andExpect(jsonPath("$[0].observations.length()").value(1))
            .andExpect(jsonPath("$[0].observations[0].citations.length()").value(2))
            .andExpect(jsonPath("$[0].observations[0].citations[0].grounded").value(true))
            .andExpect(jsonPath("$[0].observations[0].citations[1].grounded").value(false))
            .andExpect(jsonPath("$[0].insufficientSample[0].citations[0].grounded").value(true))
            .andExpect(jsonPath("$[0].citationsTotal").value(3))
            .andExpect(jsonPath("$[0].citationsUngrounded").value(1))
            .andExpect(jsonPath("$[0].inputTokens").value(111))
            .andExpect(jsonPath("$[0].outputTokens").value(22))
            .andExpect(jsonPath("$[0].tagNames['Tag A']").value("Discrecional"))
            .andExpect(jsonPath("$[0].scope.periodPreset").value("all"))
            .andExpect(jsonPath("$[0].scope.timeZone").value("UTC"))

        val runId = com.jayway.jsonpath.JsonPath.read<String>(started.response.contentAsString, "$.id")
        mockMvc.perform(
            delete("/api/profiles/$profileId/ai-analysis/$runId").cookie(session).with(csrf()),
        )
            .andExpect(status().isOk)
        mockMvc.perform(get("/api/profiles/$profileId/ai-analysis").cookie(session))
            .andExpect(jsonPath("$.length()").value(0))
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

    /** GET /taxonomy/groups seeds the default taxonomy; the first origen tag is the anonymized "Tag A". */
    private fun firstTagId(session: Cookie): String {
        val result = mockMvc.perform(get("/api/taxonomy/groups").cookie(session))
            .andExpect(status().isOk)
            .andReturn()
        return com.jayway.jsonpath.JsonPath.read<String>(result.response.contentAsString, "$[0].tags[0].id")
    }
}
