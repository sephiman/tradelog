// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import com.sephilabs.tradelog.common.errors.AppException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** One user switch governs every AI feature; each answers this code when it is off. */
const val AI_FEATURES_DISABLED = "AI_FEATURES_DISABLED"

/** [model] and [providerHost] name where the aggregates go, for the setting's help text. */
data class AiSettingsDto(
    val enabled: Boolean,
    val model: String,
    val providerHost: String,
)

data class AiSettingsRequest(val enabled: Boolean)

@Service
class AiSettingsService(
    private val availability: AiAvailability,
    private val settings: AiSettingsRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun get(userId: UUID): AiSettingsDto = toDto(isEnabled(userId))

    @Transactional
    fun update(userId: UUID, enabled: Boolean): AiSettingsDto {
        availability.requireConfigured()
        val row = settings.findById(userId).orElseGet { AiSettings(userId = userId) }
        row.enabled = enabled
        row.updatedAt = Instant.now()
        settings.save(row)
        log.info("ai_settings_updated userId={} enabled={}", userId, enabled)
        return toDto(enabled)
    }

    /** 404 when the instance has no provider, like every other AI path. */
    @Transactional(readOnly = true)
    fun isEnabled(userId: UUID): Boolean {
        availability.requireConfigured()
        return settings.findById(userId).map { it.enabled }.orElse(false)
    }

    fun requireEnabled(userId: UUID) {
        if (!isEnabled(userId)) throw AppException.conflict(AI_FEATURES_DISABLED)
    }

    private fun toDto(enabled: Boolean): AiSettingsDto {
        val provider = availability.requireConfigured()
        return AiSettingsDto(enabled = enabled, model = provider.model, providerHost = provider.host)
    }
}
