// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.mail

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Captures outgoing mail instead of talking SMTP. */
class RecordingEmailSender : EmailSender {
    val sent = CopyOnWriteArrayList<OutgoingEmail>()

    @Volatile
    var nextOk = true

    override fun send(mail: OutgoingEmail): SendResult {
        sent.add(mail)
        return SendResult(nextOk, if (nextOk) null else "535-5.7.8 Username and Password not accepted")
    }

    fun to(address: String): List<OutgoingEmail> = sent.filter { it.to == address }
}

/** Lets a test pretend SMTP is absent without booting a second Spring context. [forced] null = real. */
class TogglableMailAvailability(smtp: SmtpSettings) : MailAvailability(smtp) {
    @Volatile
    var forced: Boolean? = null

    override val enabled: Boolean get() = forced ?: super.enabled
}

@TestConfiguration
class MailTestDoublesConfig {

    @Bean
    @Primary
    fun recordingEmailSender() = RecordingEmailSender()

    @Bean
    @Primary
    fun togglableMailAvailability(smtp: SmtpSettings) = TogglableMailAvailability(smtp)

    /** Replaces the production pool (excluded from the test profile): runs the dispatch on a worker,
     *  as production does, but blocks, so the recorded mail exists by the time the response lands. */
    @Bean("mailExecutor")
    fun mailExecutor(): Executor {
        val worker = Executors.newSingleThreadExecutor()
        return Executor { task -> worker.submit(task).get() }
    }
}
