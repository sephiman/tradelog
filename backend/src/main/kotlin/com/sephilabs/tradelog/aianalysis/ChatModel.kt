// SPDX-License-Identifier: AGPL-3.0-only
package com.sephilabs.tradelog.aianalysis

import java.util.UUID

/** The edge to the language model, so the analysis logic is testable without a provider. */
interface AiChatClient {
    /** Throws [AiChatException] once the call (and its one retry) has failed. */
    fun complete(prompt: ChatPrompt): ChatAnswer
}

data class ChatPrompt(val system: String, val user: String, val sessionId: UUID)

data class ChatAnswer(
    val content: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val reasoningTokens: Int?,
    val latencyMs: Long,
    val attempts: Int,
)

class AiChatException(message: String, val attempts: Int, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Stands in when no provider is configured; every caller is behind [AiAvailability], so reaching it is a bug. */
object UnconfiguredAiChatClient : AiChatClient {
    override fun complete(prompt: ChatPrompt): ChatAnswer =
        throw IllegalStateException("AI provider is not configured")
}
