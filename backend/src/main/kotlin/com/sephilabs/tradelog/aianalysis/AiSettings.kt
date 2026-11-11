// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/** Per-user opt-in for every AI feature. Off by default; no row reads as off. */
@Entity
@Table(name = "ai_settings")
class AiSettings(
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

interface AiSettingsRepository : JpaRepository<AiSettings, UUID>
