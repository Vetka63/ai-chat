package dev.aichallenge.local.chat

import dev.aichallenge.local.generation.Generation
import dev.aichallenge.local.generation.LlmRole
import dev.aichallenge.local.generation.RunStatus
import jakarta.validation.constraints.*

/** Модель фиксируется при создании чата; настройки генерации выбираются для каждого сообщения. */
data class CreateChatRequest(@field:NotBlank val model: String, @field:Size(max = 100) val title: String = "")

/** requestId — ключ идемпотентности: потеря HTTP-ответа не должна создавать второй пользовательский ход. */
data class SendMessageRequest(
    @field:Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") val requestId: String,
    @field:NotBlank @field:Size(max = 16000) val content: String,
    @field:DecimalMin("0.0") @field:DecimalMax("2.0") val temperature: Double = 0.7,
    val thinking: Boolean = false,
    @field:Min(1) @field:Max(32768) val maxOutputTokens: Int? = null,
)

/** Сообщение и состояние его хода. Параметры сохраняются у USER, метрики — у ASSISTANT. */
data class ChatMessage(
    val id: String,
    val requestId: String,
    val role: LlmRole,
    val content: String,
    val status: RunStatus,
    val createdAt: String,
    val parameters: SendMessageRequest? = null,
    val generation: Generation? = null,
    val elapsedMilliseconds: Long? = null,
    val contextMessageCount: Int? = null,
    val error: String? = null,
)

/** Полная серверная история одного чата. Системная инструкция хранится отдельно от переписки. */
data class Chat(
    val id: String,
    val title: String,
    val model: String,
    val createdAt: String,
    val updatedAt: String,
    val systemPrompt: String,
    val messages: List<ChatMessage> = emptyList(),
)

data class ChatSummary(val id: String, val title: String, val model: String, val updatedAt: String, val messageCount: Int)
class ChatMissing : RuntimeException("Чат не найден.")
class ChatConflict(message: String) : RuntimeException(message)
