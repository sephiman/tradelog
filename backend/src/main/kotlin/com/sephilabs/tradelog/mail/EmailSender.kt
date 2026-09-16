// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.mail

data class OutgoingEmail(val to: String, val subject: String, val body: String)

/** One delivery attempt. A failure comes back as `ok=false` with the server's response rather than
 *  being thrown: callers log it and move on, the same fire-and-log contract as a connector call. */
data class SendResult(val ok: Boolean, val description: String?)

interface EmailSender {
    fun send(mail: OutgoingEmail): SendResult
}

/** Bound when SMTP is not configured. Nothing should reach it: every mail-backed path is hidden then. */
object DisabledEmailSender : EmailSender {
    override fun send(mail: OutgoingEmail): SendResult = SendResult(false, "SMTP not configured")
}
