// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.mail

import org.springframework.stereotype.Component

/** The single switch behind every mail-backed path: the self-service password reset (hidden and
 *  404 without it) and the verified half of the email change (which falls back to a direct swap). */
@Component
class MailAvailability(private val smtp: SmtpSettings) {
    val enabled: Boolean get() = smtp is SmtpSettings.Configured
}
