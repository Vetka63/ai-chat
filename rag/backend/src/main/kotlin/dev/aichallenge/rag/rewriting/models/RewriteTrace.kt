package dev.aichallenge.rag.rewriting.models

import dev.aichallenge.rag.answering.models.*

/** Воспроизводимый результат технической стадии; исходный вопрос не заменяется этим текстом при ответе. */
data class RewriteTrace(val query: String, val model: String, val finishReason: String, val milliseconds: Long, val usage: TokenUsage?, val estimatedCost: CostEstimate?, val messages: List<LlmMessage>, val rawResponse: String)
