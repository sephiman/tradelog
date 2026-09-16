// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.mail

import com.sephilabs.tradelog.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SmtpSettingsTest {

    private val complete = AppProperties.Mail(
        host = "smtp.gmail.com",
        port = 587,
        username = "me@gmail.com",
        password = "abcd efgh ijkl mnop",
        from = "tradelog <me@gmail.com>",
    )

    @Test
    fun `nothing set resolves to absent`() {
        assertThat(SmtpSettings.resolve(AppProperties.Mail(), "")).isEqualTo(SmtpSettings.Absent)
    }

    @Test
    fun `a public url alone is not an attempt to configure mail`() {
        assertThat(SmtpSettings.resolve(AppProperties.Mail(), "https://tradelog.example.com"))
            .isEqualTo(SmtpSettings.Absent)
    }

    @Test
    fun `a partial group names every missing variable, the public url included`() {
        val partial = AppProperties.Mail(host = "smtp.gmail.com", username = "me@gmail.com")

        val resolved = SmtpSettings.resolve(partial, "")

        assertThat(resolved).isEqualTo(
            SmtpSettings.PartiallyConfigured(listOf("SMTP_PASSWORD", "MAIL_FROM", "APP_PUBLIC_URL")),
        )
    }

    @Test
    fun `a group missing only the public url is still not configured`() {
        assertThat(SmtpSettings.resolve(complete, "  "))
            .isEqualTo(SmtpSettings.PartiallyConfigured(listOf("APP_PUBLIC_URL")))
    }

    @Test
    fun `a complete group is configured with the public url normalized`() {
        val resolved = SmtpSettings.resolve(complete, " https://tradelog.example.com/ ") as SmtpSettings.Configured

        assertThat(resolved.publicUrl).isEqualTo("https://tradelog.example.com")
        assertThat(resolved.host).isEqualTo("smtp.gmail.com")
        assertThat(resolved.port).isEqualTo(587)
        assertThat(resolved.startTls).isTrue()
        assertThat(resolved.from).isEqualTo("tradelog <me@gmail.com>")
    }
}
