// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class AiAnalysisStartRequest(val payload: JsonNode?)

/** The dashboard filter state the run was produced under. */
data class AiAnalysisScopeDto(
    val periodPreset: String,
    val rangeFrom: String?,
    val rangeTo: String?,
    val exchange: String,
    /** The anonymized label ("Tag A") as the model saw it; the UI maps it back via [AiAnalysisRunDto.tagNames]. */
    val origenLabel: String?,
    val timeZone: String,
    val sampleSize: Int,
)

data class AiAnalysisCitationDto(
    val path: String,
    val value: JsonNode?,
    val grounded: Boolean,
)

data class AiAnalysisObservationDto(
    val claim: String,
    val sampleSize: Int?,
    val confidence: String?,
    val citations: List<AiAnalysisCitationDto>,
)

data class AiAnalysisInsufficientDto(
    val topic: String,
    val reason: String,
    val citations: List<AiAnalysisCitationDto>,
)

data class AiAnalysisRunDto(
    val id: UUID,
    val status: AiAnalysisRunStatus,
    val createdAt: Instant,
    val finishedAt: Instant?,
    val scope: AiAnalysisScopeDto,
    val model: String,
    val promptVersion: String,
    /** Anonymized label -> the tag's real name, resolved at read time. Labels may outlive their tag. */
    val tagNames: Map<String, String>,
    val observations: List<AiAnalysisObservationDto>,
    val insufficientSample: List<AiAnalysisInsufficientDto>,
    val observationsCount: Int?,
    val insufficientSampleCount: Int?,
    val citationsTotal: Int?,
    val citationsUngrounded: Int?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val reasoningTokens: Int?,
    val latencyMs: Long?,
    val errorCode: String?,
)
