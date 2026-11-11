// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import com.sephilabs.tradelog.common.errors.AppException
import com.sephilabs.tradelog.identity.user.User
import com.sephilabs.tradelog.taxonomy.TagRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executor

/** Buckets below this sample are dropped from the payload's breakdowns by the frontend builder;
 *  the whole analysis needs at least this many closed trades to mean anything. */
const val AI_ANALYSIS_MIN_SAMPLE = 10

/** Defense against a runaway client: the aggregates are a few KB, never megabytes. */
const val AI_ANALYSIS_MAX_PAYLOAD_CHARS = 100_000

/**
 * Starts and lists style analysis runs. The frontend builds the aggregate payload with the same
 * functions that render the dashboard, anonymizes Origen tags, and posts it; the provider
 * conversation itself runs off the request thread in [AiAnalysisRunner].
 */
@Service
class AiAnalysisService(
    private val availability: AiAvailability,
    private val aiSettings: AiSettingsService,
    private val runs: AiAnalysisRunRepository,
    private val tags: TagRepository,
    private val runner: AiAnalysisRunner,
    private val limiter: AiAnalysisRateLimiter,
    transactionManager: PlatformTransactionManager,
    @Qualifier("aiAnalysisExecutor") private val executor: Executor,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val mapper = JsonMapper.builder().build()

    /** Runs are re-verified on every read: grounding is a pure function of payload + answer. */
    @Transactional(readOnly = true)
    fun list(profileId: UUID): List<AiAnalysisRunDto> {
        availability.requireConfigured()
        val stored = runs.findAllByProfileIdOrderByCreatedAtDesc(profileId)
        val tagNames = resolveTagNames(stored)
        return stored.map { it.toDto(tagNames) }
    }

    @Transactional
    fun delete(profileId: UUID, runId: UUID) {
        availability.requireConfigured()
        val run = runs.findByIdAndProfileId(runId, profileId)
            ?: throw AppException.notFound("AI_ANALYSIS_RUN_NOT_FOUND")
        runs.delete(run)
        log.info("ai_analysis_run_deleted run={} profile={}", runId, profileId)
    }

    /** Snapshots the aggregates and hands them to the runner. Not transactional: the run row must be
     *  committed before the worker reads it. */
    fun start(profileId: UUID, user: User, request: AiAnalysisStartRequest): AiAnalysisRunDto {
        val provider = availability.requireConfigured()
        aiSettings.requireEnabled(user.id)
        if (!limiter.tryAcquire(user.id)) throw AppException.tooManyRequests()
        if (request.payload == null || !request.payload.isObject) {
            throw AppException.badRequest("AI_ANALYSIS_INVALID_PAYLOAD")
        }
        // tagMap stays out of what the model ever sees: it carries the user's own tag ids. The key is
        // stripped even when empty, so payloadJson never carries a tagMap the model could echo back.
        val tagMap = extractTagMap(request.payload)
        val payload = if (request.payload.has("tagMap")) copyWithoutTagMap(request.payload) else request.payload
        val scope = payload.path("scope")
        val summary = payload.path("summary")
        if (!scope.isObject || !summary.isObject) throw AppException.badRequest("AI_ANALYSIS_INVALID_PAYLOAD")
        val sampleSize = summary.path("n").takeIf { it.isNumber }?.asInt()
            ?: throw AppException.badRequest("AI_ANALYSIS_INVALID_PAYLOAD")
        if (sampleSize < AI_ANALYSIS_MIN_SAMPLE) {
            throw AppException.badRequest("AI_ANALYSIS_SAMPLE_TOO_SMALL", AI_ANALYSIS_MIN_SAMPLE)
        }
        val payloadJson = mapper.writeValueAsString(payload)
        if (payloadJson.length > AI_ANALYSIS_MAX_PAYLOAD_CHARS) {
            throw AppException.badRequest("AI_ANALYSIS_INVALID_PAYLOAD")
        }

        val run = runs.save(
            AiAnalysisRun(
                profileId = profileId,
                userId = user.id,
                model = provider.model,
                promptVersion = TradingStylePrompt.VERSION,
                locale = user.locale.take(2).lowercase().ifBlank { "en" },
                periodPreset = scope.path("periodPreset").asString("").ifBlank { "custom" },
                rangeFrom = scope.path("rangeFrom").takeIf { it.isTextual }?.asString()?.let { parseDate(it) },
                rangeTo = scope.path("rangeTo").takeIf { it.isTextual }?.asString()?.let { parseDate(it) },
                exchangeFilter = scope.path("exchange").asString("ALL").ifBlank { "ALL" },
                origenTagId = scope.path("origen").takeIf { it.isTextual }?.asString()
                    ?.let { label -> tagMap?.entries?.firstOrNull { it.key == label }?.value }
                    ?.let { id -> runCatching { UUID.fromString(id) }.getOrNull() },
                timeZone = scope.path("timeZone").asString("UTC").ifBlank { "UTC" },
                sampleSize = sampleSize,
                payloadJson = payloadJson,
                tagMap = tagMap?.let { mapper.writeValueAsString(it) },
            ),
        )
        log.info(
            "ai_analysis_run_started run={} profile={} user={} sample={} model={} prompt={} locale={}",
            run.id, profileId, user.id, sampleSize, run.model, run.promptVersion, run.locale,
        )
        executor.execute { runner.execute(run.id) }
        return run.toDto(resolveTagNames(listOf(run)))
    }

    /** Runs left RUNNING by a crash or restart can never finish: mark them so history stays truthful. */
    @EventListener(ApplicationReadyEvent::class)
    fun interruptLeftoverRuns() {
        val leftovers = runs.findAllByStatus(AiAnalysisRunStatus.RUNNING)
        if (leftovers.isEmpty()) return
        tx.execute {
            leftovers.forEach {
                it.status = AiAnalysisRunStatus.INTERRUPTED
                it.errorCode = "INTERRUPTED"
                it.finishedAt = java.time.Instant.now()
            }
        }
        log.warn("ai_analysis_runs_interrupted count={}", leftovers.size)
    }

    // --- Validation helpers -------------------------------------------------

    /** label -> tagId, kept only in the database; the model's payload never carries it. */
    private fun extractTagMap(payload: JsonNode): Map<String, String>? {
        val node = payload.path("tagMap")
        if (!node.isObject) return null
        val map = node.properties().associate { it.key to it.value.asString("") }
        return map.takeIf { m -> m.isNotEmpty() && m.values.all { it.isNotBlank() } }
    }

    private fun copyWithoutTagMap(payload: JsonNode): JsonNode =
        mapper.createObjectNode().apply {
            payload.properties().forEach { (name, value) -> if (name != "tagMap") set(name, value) }
        }

    private fun parseDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value) }.getOrNull()

    // --- DTO assembly -------------------------------------------------------

    private fun resolveTagNames(stored: List<AiAnalysisRun>): Map<String, String> {
        val ids = stored.mapNotNull { it.tagMap }.mapNotNull { parseTagMap(it) }
            .flatMap { it.values }.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        if (ids.isEmpty()) return emptyMap()
        val names = tags.findAllById(ids).associateBy { it.id }
        return stored.mapNotNull { it.tagMap }.mapNotNull { parseTagMap(it) }
            .flatMap { it.entries }.associate { (label, id) ->
                val uuid = runCatching { UUID.fromString(id) }.getOrNull()
                label to (uuid?.let { names[it]?.name } ?: label)
            }
    }

    private fun parseTagMap(json: String): Map<String, String>? =
        runCatching { mapper.readTree(json) }.getOrNull()
            ?.takeIf { it.isObject }
            ?.let { node -> node.properties().associate { it.key to it.value.asString("") } }

    private fun AiAnalysisRun.toDto(tagNames: Map<String, String>): AiAnalysisRunDto {
        val payload = runCatching { mapper.readTree(payloadJson) }.getOrNull()
        val result = resultJson?.let { runCatching { mapper.readTree(it) }.getOrNull() }
        val tagMap = tagMap?.let { parseTagMap(it) }
        val observations = result?.path("observations")?.takeIf { it.isArray }?.toList()?.map { obs ->
            AiAnalysisObservationDto(
                claim = obs.path("claim").asString(""),
                sampleSize = obs.path("sampleSize").takeIf { it.isNumber }?.asInt(),
                confidence = obs.path("confidence").takeIf { it.isTextual }?.asString(),
                citations = citationsOf(obs, payload),
            )
        } ?: emptyList()
        val insufficient = result?.path("insufficientSample")?.takeIf { it.isArray }?.toList()?.map { item ->
            AiAnalysisInsufficientDto(
                topic = item.path("topic").asString(""),
                reason = item.path("reason").asString(""),
                citations = citationsOf(item, payload),
            )
        } ?: emptyList()
        return AiAnalysisRunDto(
            id = id,
            status = status,
            createdAt = createdAt,
            finishedAt = finishedAt,
            scope = AiAnalysisScopeDto(
                periodPreset = periodPreset,
                rangeFrom = rangeFrom?.toString(),
                rangeTo = rangeTo?.toString(),
                exchange = exchangeFilter,
                origenLabel = origenTagId?.let { tagId ->
                    tagMap?.entries?.firstOrNull { it.value == tagId.toString() }?.key
                },
                timeZone = timeZone,
                sampleSize = sampleSize,
            ),
            model = model,
            promptVersion = promptVersion,
            tagNames = tagNames,
            observations = observations,
            insufficientSample = insufficient,
            observationsCount = observationsCount,
            insufficientSampleCount = insufficientSampleCount,
            citationsTotal = citationsTotal,
            citationsUngrounded = citationsUngrounded,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            latencyMs = latencyMs,
            errorCode = errorCode,
        )
    }

    private fun citationsOf(node: JsonNode, payload: JsonNode?): List<AiAnalysisCitationDto> =
        node.path("citations").takeIf { it.isArray }?.toList()?.map { cite ->
            AiAnalysisCitationDto(
                path = cite.path("path").asString(""),
                value = cite.path("value").takeIf { !it.isMissingNode },
                grounded = payload?.let { GroundingVerifier.check(it, cite).grounded } ?: false,
            )
        } ?: emptyList()
}
