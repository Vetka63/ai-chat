package dev.aichallenge.rag.answering.ports

import dev.aichallenge.rag.answering.models.*

/** Генератор заменяем независимо от поиска, сборки промпта и интерфейса. */
interface LlmClient {
    fun settings(): AnswerSettings
    fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion
    /** JSON-режим для технических стадий; обычная генерация ответа его не использует. */
    fun completeJson(messages: List<LlmMessage>, maxOutputTokens: Int): LlmCompletion = complete(messages, maxOutputTokens)
}
