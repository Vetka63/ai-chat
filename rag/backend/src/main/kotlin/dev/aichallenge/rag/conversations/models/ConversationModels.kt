package dev.aichallenge.rag.conversations.models

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.conversations.enums.TurnStatus
import dev.aichallenge.rag.grounding.models.GroundedResult
import dev.aichallenge.rag.taskmemory.models.*
import jakarta.validation.constraints.*

/** Индекс и retrieval-настройки закрепляются при создании чата; history не переезжает в другой корпус. */
data class ConversationSettings(
    @field:NotBlank @field:Size(max = 128) val indexId: String,
    @field:Min(1) @field:Max(20) val candidateTopK: Int = 10,
    @field:Min(1) @field:Max(10) val finalTopK: Int = 5,
    @field:DecimalMin("-1") @field:DecimalMax("1") val similarityThreshold: Double = .65,
    @field:Min(300) @field:Max(60000) val contextMaxCharacters: Int = 16000,
    @field:Min(1) @field:Max(12) val historyTurns: Int = 6,
    @field:Min(1000) @field:Max(20000) val historyMaxCharacters: Int = 10000,
    @field:Min(1) @field:Max(32768) val maxOutputTokens: Int? = null,
)

/** Название не влияет на system prompt, @Valid настроек применяется контроллером. */
data class CreateConversation(@field:NotBlank @field:Size(max = 100) val title: String, @field:jakarta.validation.Valid val settings: ConversationSettings)

/** client-generated requestId даёт идемпотентность, revision защищает от устаревшей вкладки. */
data class SendTurn(@field:NotBlank @field:Size(max = 80) val requestId: String, @field:NotBlank @field:Size(max = 2000) val question: String, @field:Min(0) val expectedRevision: Long)

/** Метаданные чата без сообщений, удобные для списка; revision растёт при begin/finish. */
data class Conversation(val id: String, val title: String, val settings: ConversationSettings, val snapshotId: String, val createdAt: String, val updatedAt: String, val revision: Long)

/** Durable exchange: пользователь не исчезает даже при ошибке провайдера или рестарте. */
data class ConversationTurn(val id: String, val requestId: String, val question: String, val createdAt: String, val status: TurnStatus, val preparation: PreparationTrace? = null, val result: GroundedResult? = null, val issue: String? = null, val memoryAfter: TaskMemory? = null, val includedHistoryTurnIds: List<String> = emptyList(), val omittedHistoryTurnCount: Int = 0, val totalUsage: TokenUsage? = null, val estimatedCost: CostEstimate? = null, val llmStagesAttempted: Int = 0)

/** История и память читаются согласованно; факты никогда не шарятся между чатами. */
data class ConversationDetail(val conversation: Conversation, val memory: TaskMemory, val turns: List<ConversationTurn>)

/** Внутренний receipt claim: повторный requestId не вызывает второй платный запрос. */
data class TurnClaim(val detail: ConversationDetail, val turn: ConversationTurn, val owned: Boolean)
