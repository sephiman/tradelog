// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import com.sephilabs.tradelog.common.errors.AppException
import org.springframework.stereotype.Component

/** The instance-level switch: without a configured provider every AI endpoint answers as if it did not exist. */
@Component
class AiAvailability(private val settings: AiProviderSettings) {
    val enabled: Boolean get() = settings is AiProviderSettings.Configured

    fun requireConfigured(): AiProviderSettings.Configured {
        if (!enabled) throw AppException.notFound()
        return settings as AiProviderSettings.Configured
    }
}
