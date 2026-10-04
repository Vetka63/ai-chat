package dev.aichallenge.rag.taskmemory.models

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.taskmemory.enums.MemoryLayer

/** Происхождение памяти — точная цитата сообщения пользователя, ID назначает сервер. */
data class MemoryFact(val layer: MemoryLayer, val key: String, val value: String, val sourceTurnId: String, val quote: String)

/** Компактный накопительный state, отдельно от полной истории; максимум 12 записей каждого слоя. */
data class TaskMemory(val facts: List<MemoryFact> = emptyList())

/** Недоверенная модель предлагает patch; сервер проверяет цитаты и применяет его атомарно. */
data class MemoryChange(val layer: MemoryLayer, val key: String, val value: String?, val quote: String)

/** Вход подготовки: только ограниченный хвост и проверенная память, без текстов книги. */
data class DialogueContext(val turnId: String, val question: String, val memory: TaskMemory, val recent: List<DialogueItem>, val omittedTurnCount: Int)

/** Прошлый обмен: разрешённый вопрос сохраняет тему, даже если grounded-ответ не прошёл проверку. */
data class DialogueItem(val turnId: String, val user: String, val assistant: String?, val resolvedQuestion: String? = null)

/** Прозрачная подготовка: отдельно query, patch, вход модели, usage и исходный непроверенный JSON. */
data class PreparationTrace(val query: String, val memory: TaskMemory, val changes: List<MemoryChange>, val model: String, val finishReason: String, val milliseconds: Long, val usage: TokenUsage?, val estimatedCost: CostEstimate?, val messages: List<LlmMessage>, val rawJson: String, val issues: List<String>)
