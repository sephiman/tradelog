// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import com.sephilabs.tradelog.identity.AuthHttpTestBase
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Without a configured provider every AI endpoint answers 404, as if the feature did not exist —
 *  reads and deletes included, not only the paths that would call the provider. */
class AiFeaturesHiddenTest : AuthHttpTestBase() {

    @Test
    fun `every ai endpoint answers 404 with no provider configured`() {
        val session = sessionOf(register(newEmail("ai-hidden")))
        val profileId = createProfile(session)

        mockMvc.perform(get("/api/features").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ai").value(false))

        mockMvc.perform(get("/api/ai/settings").cookie(session)).andExpect(status().isNotFound)
        mockMvc.perform(
            put("/api/ai/settings").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"enabled":true}"""),
        ).andExpect(status().isNotFound)

        mockMvc.perform(get("/api/profiles/$profileId/ai-analysis").cookie(session))
            .andExpect(status().isNotFound)
        mockMvc.perform(
            post("/api/profiles/$profileId/ai-analysis").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"payload": {"scope":{},"summary":{"n":12}}}"""),
        ).andExpect(status().isNotFound)
        mockMvc.perform(
            delete("/api/profiles/$profileId/ai-analysis/${java.util.UUID.randomUUID()}").cookie(session).with(csrf()),
        ).andExpect(status().isNotFound)
    }

    private fun createProfile(session: Cookie): String {
        val result = mockMvc.perform(
            post("/api/profiles").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"kind":"PERSONAL","name":"Main"}"""),
        ).andExpect(status().isCreated).andReturn()
        return com.jayway.jsonpath.JsonPath.read<String>(result.response.contentAsString, "$.id")
    }
}
