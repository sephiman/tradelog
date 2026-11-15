// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** One provider call per run: send the aggregates, parse the structured answer, verify every
 *  citation against the payload, and record the run. Nothing here writes anything else. */
@Component
class AiAnalysisRunner(
    private val model: AiChatClient,
    private val runs: AiAnalysisRunRepository,
    transactionManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val mapper = JsonMapper.builder().build()

    fun execute(runId: UUID) {
        val previous = MDC.getCopyOfContextMap()
        MDC.put("aiRunId", runId.toString())
        try {
            val run = runs.findById(runId).orElse(null) ?: return
            MDC.put("profileId", run.profileId.toString())
            val payload = mapper.readTree(run.payloadJson)
            val prior = selectPriorRun(run)
            val priorContext = prior?.let { buildPriorContext(it) }
            val prompt = ChatPrompt(
                system = TradingStylePrompt.system(run.locale),
                user = TradingStylePrompt.user(run.payloadJson, priorContext),
                sessionId = UUID.randomUUID(),
            )
            log.info(
                "ai_analysis_sent run={} profile={} session={} sample={} system_chars={} user_chars={}",
                runId, run.profileId, prompt.sessionId, run.sampleSize, prompt.system.length, prompt.user.length,
            )
            val answer = try {
                model.complete(prompt)
            } catch (ex: AiChatException) {
                log.warn(
                    "ai_analysis_run run={} profile={} attempts={} outcome=failed error={}",
                    runId, run.profileId, ex.attempts, ex.message,
                )
                finish(runId, AiAnalysisRunStatus.FAILED, errorCode = "MODEL_ERROR")
                return
            }
            val parsed = JsonObjectReader.first(answer.content)
            if (parsed == null || !isWellFormed(parsed)) {
                log.warn(
                    "ai_analysis_run run={} profile={} outcome=failed error=unreadable_answer",
                    runId, run.profileId,
                )
                finish(runId, AiAnalysisRunStatus.FAILED, errorCode = "UNREADABLE_ANSWER", answer = answer)
                return
            }
            val grounding = GroundingVerifier.verify(payload, parsed)
            val completed = finish(runId, AiAnalysisRunStatus.COMPLETED, answer = answer) {
                it.resultJson = mapper.writeValueAsString(parsed)
                it.observationsCount = parsed.path("observations").size()
                it.insufficientSampleCount = parsed.path("insufficientSample").size()
                it.citationsTotal = grounding.total
                it.citationsUngrounded = grounding.ungrounded
            }
            log.info(
                "ai_analysis_run run={} profile={} user={} status={} prompt={} model={} observations={} " +
                    "insufficient={} citations={} ungrounded={} attempts={} latency_ms={} " +
                    "input_tokens={} output_tokens={} reasoning_tokens={}",
                runId, run.profileId, run.userId, completed?.status, run.promptVersion, run.model,
                completed?.observationsCount, completed?.insufficientSampleCount, grounding.total,
                grounding.ungrounded, answer.attempts, answer.latencyMs, answer.inputTokens,
                answer.outputTokens, answer.reasoningTokens,
            )
        } catch (ex: Exception) {
            log.error("ai_analysis_run run={} status=failed", runId, ex)
            finish(runId, AiAnalysisRunStatus.FAILED, errorCode = "INTERNAL_ERROR")
        } finally {
            if (previous != null) MDC.setContextMap(previous) else MDC.clear()
        }
    }

    /** Prefer the most recent completed run under the same filter scope with a different sample or date end. */
    private fun selectPriorRun(current: AiAnalysisRun): AiAnalysisRun? {
        val candidates = runs.findAllByProfileIdAndStatusOrderByCreatedAtDesc(
            current.profileId,
            AiAnalysisRunStatus.COMPLETED,
        ).filter { it.id != current.id }
        if (candidates.isEmpty()) return null
        val sameScope = candidates.filter {
            it.periodPreset == current.periodPreset &&
                it.exchangeFilter == current.exchangeFilter &&
                it.origenTagId == current.origenTagId
        }
        return sameScope.firstOrNull {
            it.sampleSize != current.sampleSize ||
                it.rangeFrom != current.rangeFrom ||
                it.rangeTo != current.rangeTo
        } ?: sameScope.firstOrNull() ?: candidates.first()
    }

    private fun buildPriorContext(prior: AiAnalysisRun): String? {
        val prevResult = prior.resultJson?.let { runCatching { mapper.readTree(it) }.getOrNull() } ?: return null
        val prevSummary = prevResult.path("summary").takeIf { it.isString }?.asString()?.trim()
        if (prevSummary.isNullOrBlank()) return null

        val prevPayload = runCatching { mapper.readTree(prior.payloadJson) }.getOrNull()
        val summaryNode = prevPayload?.path("summary")
        val metrics = buildList {
            fun addNum(label: String, node: JsonNode?) {
                if (node != null && node.isNumber) add("$label=${node.asString()}")
            }
            addNum("totalPnl", summaryNode?.path("totalPnl"))
            addNum("winRatePct", summaryNode?.path("winRatePct"))
            addNum("expectancy", summaryNode?.path("expectancy"))
            addNum("avgRR", summaryNode?.path("avgRR"))
            addNum("profitFactor", summaryNode?.path("profitFactor"))
            addNum("maxDrawdownUsd", prevPayload?.path("drawdown")?.path("maxUsd"))
            addNum("totalFees", prevPayload?.path("fees")?.path("totalFees"))
            addNum("postLossReentryN", prevPayload?.path("behavioralSignals")?.path("postLossReentry")?.path("n"))
            addNum("postLossReentryPnl", prevPayload?.path("behavioralSignals")?.path("postLossReentry")?.path("totalPnl"))
        }
        val prevImprovements = prevResult.path("improvements").takeIf { it.isArray }?.toList()
            ?.mapNotNull { it.path("title").takeIf { t -> t.isString }?.asString()?.trim()?.takeIf { s -> s.isNotBlank() } }
            ?: emptyList()
        val range = if (prior.rangeFrom != null || prior.rangeTo != null) {
            "${prior.rangeFrom ?: "..."}..${prior.rangeTo ?: "..."}"
        } else {
            "all"
        }
        return buildString {
            append("Date: ${prior.createdAt.toString().take(10)}")
            append(" | Scope: ${prior.periodPreset} ($range, exchange=${prior.exchangeFilter}, n=${prior.sampleSize})")
            if (metrics.isNotEmpty()) append("\nMetrics: ${metrics.joinToString(", ")}")
            append("\nSummary: $prevSummary")
            if (prevImprovements.isNotEmpty()) append("\nPrior improvements: ${prevImprovements.joinToString("; ")}")
        }
    }

    /** Observations with a claim each; anything less is half an answer, and half an answer is no answer. */
    private fun isWellFormed(parsed: JsonNode): Boolean =
        parsed.path("observations").isArray &&
            parsed.path("observations").size() > 0 &&
            parsed.path("observations").toList().all { obs ->
                obs.isObject && obs.path("claim").isString && obs.path("claim").asString().isNotBlank()
            }

    private fun finish(
        runId: UUID,
        status: AiAnalysisRunStatus,
        errorCode: String? = null,
        answer: ChatAnswer? = null,
        counts: (AiAnalysisRun) -> Unit = {},
    ) = tx.execute {
        runs.findById(runId).orElse(null)?.apply {
            counts(this)
            errorCode?.let { this.errorCode = it }
            inputTokens = answer?.inputTokens
            outputTokens = answer?.outputTokens
            reasoningTokens = answer?.reasoningTokens
            latencyMs = answer?.latencyMs
            this.status = status
            finishedAt = Instant.now()
        }
    }
}
