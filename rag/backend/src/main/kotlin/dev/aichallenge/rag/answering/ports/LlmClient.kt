package dev.aichallenge.rag.answering.ports

import dev.aichallenge.rag.answering.models.*

/** Генератор заменяем независимо от поиска, сборки промпта и интерфейса. */
interface LlmClient {
    fun settings(): AnswerSettings
    fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion
    /** JSON для rewrite и grounded-ответа; null не навязывает собственный output cap. */
    fun completeJson(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion = complete(messages, maxOutputTokens)
}
