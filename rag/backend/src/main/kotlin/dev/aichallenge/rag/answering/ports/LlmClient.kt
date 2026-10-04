package dev.aichallenge.rag.answering.ports

import dev.aichallenge.rag.answering.models.*

/** Генератор заменяем независимо от поиска, сборки промпта и интерфейса. */
interface LlmClient {
    fun settings(): AnswerSettings
    fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion
    /** JSON для rewrite и grounded-ответа; null не навязывает собственный output cap. */
    fun completeJson(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion = complete(messages, maxOutputTokens)
    /** Независимый профиль подготовки памяти; default сохраняет совместимость прежних адаптеров/fixtures. */
    fun completePreparationJson(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion = completeJson(messages, maxOutputTokens)
    /** Независимая генерация с источниками; не меняет модели сравнения дней 22–23. */
    fun completeGroundedJson(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion = completeJson(messages, maxOutputTokens)
    /** Отдельный профиль смысловой проверки; старые адаптеры и fixtures используют свой JSON-путь. */
    fun completeVerifiedJson(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion = completeJson(messages, maxOutputTokens)
}
