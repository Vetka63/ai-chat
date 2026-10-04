package dev.aichallenge.rag.answering.models

import dev.aichallenge.rag.answering.enums.AnswerMode
import dev.aichallenge.rag.retrieval.models.SearchHit
import jakarta.validation.constraints.*

/** Настройки одного независимого вопроса. Бюджет относится к тексту чанков, не к лимиту модели. */
data class AnswerRequest(
    @field:NotBlank @field:Size(max = 2000) val question: String,
    val mode: AnswerMode,
    val indexId: String? = null,
    @field:Min(1) @field:Max(10) val topK: Int = 5,
    @field:Min(300) @field:Max(60000) val contextMaxCharacters: Int = 16000,
    @field:Min(1) @field:Max(32768) val maxOutputTokens: Int? = null,
)

/** Только публичные сообщения; системные инструкции отделены от недоверенных материалов книги. */
data class LlmMessage(val role: String, val content: String)

/** Usage берётся из ответа API. Отсутствие измерения не заменяется нулём или локальной оценкой. */
data class TokenUsage(val promptTokens: Long, val completionTokens: Long, val totalTokens: Long, val cacheHitTokens: Long?, val cacheMissTokens: Long?)

/** Результат генератора без текстов скрытых рассуждений и без секретов. */
data class LlmCompletion(val requestId: String, val model: String, val content: String, val finishReason: String, val milliseconds: Long, val usage: TokenUsage?)

/** Диапазон оценки по опубликованным peak/off-peak тарифам, не фактическое списание баланса. */
data class CostEstimate(val minimumUsd: Double, val maximumUsd: Double, val source: String, val verifiedOn: String, val note: String)

/** Виден ровно контекст, переданный генератору; пропущенные целые чанки учитываются явно. */
data class AnswerContext(val indexId: String?, val snapshotId: String?, val retrievedCount: Int, val included: List<SearchHit>, val omittedChunkIds: List<String>, val textCharacters: Int, val maxCharacters: Int, val retrievalMilliseconds: Long, val embeddingInputTokens: Long?)

/** Прозрачный ответ режима с данными для ручного сравнения и экспорта. Это не история чата. */
data class AnswerResult(val id: String, val createdAt: String, val question: String, val mode: AnswerMode, val model: String, val temperature: Double, val thinking: String, val answer: String, val finishReason: String, val truncated: Boolean, val maxOutputTokens: Int?, val totalMilliseconds: Long, val generationMilliseconds: Long, val usage: TokenUsage?, val estimatedCost: CostEstimate?, val context: AnswerContext, val messages: List<LlmMessage>, val warnings: List<String>)

/** Доступность генерации без выдачи ключа или URL с возможными credentials. */
data class AnswerSettings(val configured: Boolean, val model: String, val temperature: Double, val thinking: String, val defaultContextMaxCharacters: Int, val maxOutputTokensDefault: Int?, val priceSource: String)

/** Контрольные вопросы — evaluation fixtures, а не документы для индексации. */
data class ControlQuestion(val id: String, val question: String, val expected: String, val expectedSourceSuffix: String, val evidenceQuote: String)
