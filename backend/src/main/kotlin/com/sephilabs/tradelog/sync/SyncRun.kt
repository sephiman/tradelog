// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.sync

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

enum class SyncTrigger { LOGIN, MANUAL, UPLOAD, SCHEDULED }
enum class RunStatus { RUNNING, SUCCESS, ERROR }

/** Audit row per sync attempt; drives UI status and observability. */
@Entity
@Table(name = "sync_runs")
class SyncRun(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "data_source_id", nullable = false, updatable = false)
    var dataSourceId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, length = 16, updatable = false)
    var trigger: SyncTrigger,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: RunStatus = RunStatus.RUNNING,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @Column(name = "inserted", nullable = false)
    var inserted: Int = 0,

    @Column(name = "updated", nullable = false)
    var updated: Int = 0,

    @Column(name = "error_code", length = 64)
    var errorCode: String? = null,
)

interface SyncRunRepository : JpaRepository<SyncRun, UUID> {
    fun findAllByDataSourceIdOrderByStartedAtDesc(dataSourceId: UUID, pageable: Pageable): List<SyncRun>
}

data class SyncRunDto(
    val id: UUID,
    val dataSourceId: UUID,
    val trigger: SyncTrigger,
    val status: RunStatus,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val inserted: Int,
    val updated: Int,
    val errorCode: String?,
) {
    companion object {
        fun of(r: SyncRun) = SyncRunDto(
            r.id, r.dataSourceId, r.trigger, r.status, r.startedAt, r.finishedAt, r.inserted, r.updated, r.errorCode,
        )
    }
}
