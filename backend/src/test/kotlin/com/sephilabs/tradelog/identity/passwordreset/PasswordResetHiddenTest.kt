// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.identity.passwordreset

import com.sephilabs.tradelog.identity.AuthHttpTestBase
import com.sephilabs.tradelog.mail.RecordingEmailSender
import com.sephilabs.tradelog.mail.TogglableMailAvailability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** The test profile configures SMTP, so "hidden" is simulated through the availability stub. */
@ResourceLock("mail-doubles")
class PasswordResetHiddenTest @Autowired constructor(
    private val availability: TogglableMailAvailability,
    private val mailer: RecordingEmailSender,
) : AuthHttpTestBase() {

    @AfterEach
    fun restore() {
        availability.forced = null
    }

    @Test
    fun `without smtp the login page is told nothing and every reset endpoint answers 404`() {
        availability.forced = false
        val mailsBefore = mailer.sent.size

        mockMvc.perform(get("/api/auth/features"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.passwordReset").value(false))
            .andExpect(jsonPath("$.emailChangeVerified").value(false))
        send("/api/auth/password-reset", """{ "email": "someone@example.com" }""").andExpect(status().isNotFound)
        send("/api/auth/password-reset/validate", """{ "token": "whatever" }""").andExpect(status().isNotFound)
        send("/api/auth/password-reset/confirm", """{ "token": "whatever", "newPassword": "password1234" }""")
            .andExpect(status().isNotFound)

        assertThat(mailer.sent).hasSize(mailsBefore)
    }

    @Test
    fun `with smtp fully configured the login page is told the reset is available`() {
        mockMvc.perform(get("/api/auth/features"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.passwordReset").value(true))
            .andExpect(jsonPath("$.emailChangeVerified").value(true))
    }

    private fun send(path: String, body: String) =
        mockMvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
}
