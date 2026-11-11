// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class AiAnalysisRunStatus { RUNNING, COMPLETED, FAILED, INTERRUPTED }

/**
 * One style analysis run. The dashboard filter state it was produced under is snapshotted on the row
 * so past runs stay interpretable; [payloadJson] is exactly what the model saw, [resultJson] its
 * parsed answer, and [tagMap] links the anonymized labels ("Tag A") to the user's tag ids — real tag
 * names never reach the provider and are re-attached only when rendering.
 */
@Entity
@Table(name = "ai_analysis_runs")
class AiAnalysisRun(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "profile_id", nullable = false, updatable = false)
    var profileId: UUID,

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: AiAnalysisRunStatus = AiAnalysisRunStatus.RUNNING,

    @Column(name = "model", nullable = false)
    var model: String,

    @Column(name = "prompt_version", nullable = false)
    var promptVersion: String,

    @Column(name = "locale", nullable = false, length = 2)
    var locale: String,

    // --- Scope snapshot (what the dashboard showed when this was produced) ---
    @Column(name = "period_preset", nullable = false)
    var periodPreset: String,

    @Column(name = "range_from")
    var rangeFrom: LocalDate? = null,

    @Column(name = "range_to")
    var rangeTo: LocalDate? = null,

    @Column(name = "exchange_filter", nullable = false)
    var exchangeFilter: String,

    @Column(name = "origen_tag_id")
    var origenTagId: UUID? = null,

    @Column(name = "time_zone", nullable = false)
    var timeZone: String,

    /** Closed trades in the analyzed set. */
    @Column(name = "sample_size", nullable = false)
    var sampleSize: Int,

    // --- Content ---
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    var payloadJson: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb")
    var resultJson: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tag_map", columnDefinition = "jsonb")
    var tagMap: String? = null,

    @Column(name = "observations_count")
    var observationsCount: Int? = null,

    @Column(name = "insufficient_sample_count")
    var insufficientSampleCount: Int? = null,

    @Column(name = "citations_total")
    var citationsTotal: Int? = null,

    @Column(name = "citations_ungrounded")
    var citationsUngrounded: Int? = null,

    @Column(name = "input_tokens")
    var inputTokens: Int? = null,

    @Column(name = "output_tokens")
    var outputTokens: Int? = null,

    @Column(name = "reasoning_tokens")
    var reasoningTokens: Int? = null,

    @Column(name = "latency_ms")
    var latencyMs: Long? = null,

    @Column(name = "error_code")
    var errorCode: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,
)

interface AiAnalysisRunRepository : JpaRepository<AiAnalysisRun, UUID> {

    fun findAllByProfileIdOrderByCreatedAtDesc(profileId: UUID): List<AiAnalysisRun>

    fun findFirstByProfileIdAndStatusOrderByCreatedAtDesc(
        profileId: UUID,
        status: AiAnalysisRunStatus,
    ): AiAnalysisRun?

    fun findByIdAndProfileId(id: UUID, profileId: UUID): AiAnalysisRun?

    fun findAllByStatus(status: AiAnalysisRunStatus): List<AiAnalysisRun>
}
