package dev.aichallenge.rag.conversations.services

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.conversations.enums.TurnStatus
import dev.aichallenge.rag.conversations.models.*
import dev.aichallenge.rag.conversations.ports.ConversationRepository
import dev.aichallenge.rag.grounding.enums.GroundedStatus
import dev.aichallenge.rag.grounding.models.*
import dev.aichallenge.rag.grounding.services.GroundingService
import dev.aichallenge.rag.indexing.ports.IndexRepository
import dev.aichallenge.rag.taskmemory.models.*
import dev.aichallenge.rag.taskmemory.ports.DialoguePreparer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Оркестратор чата: durable user → подготовка → свежий grounded-поиск → atomic answer + memory. */
@Service
class ConversationService(private val repository: ConversationRepository, private val indexes: IndexRepository, private val preparer: DialoguePreparer, private val grounding: GroundingService) {
    private val log = LoggerFactory.getLogger(javaClass)
    fun list() = repository.list()
    fun detail(id: String) = repository.detail(id)
    fun delete(id: String) = repository.delete(id)

    /** Настройки immutable: для другой конфигурации создайте отдельный чат сравнения. */
    fun create(input: CreateConversation): ConversationDetail {
        val s = input.settings
        require(input.title.isNotBlank() && input.title.length <= 100 && s.indexId.isNotBlank() && s.indexId.length <= 128 && s.candidateTopK in 1..20 && s.finalTopK in 1..10 && s.finalTopK <= s.candidateTopK && s.similarityThreshold.isFinite() && s.similarityThreshold in -1.0..1.0 && s.contextMaxCharacters in 300..60000 && s.historyTurns in 1..12 && s.historyMaxCharacters in 1000..20000 && (s.maxOutputTokens == null || s.maxOutputTokens in 1..32768))
        val now = Instant.now().toString()
        return repository.create(Conversation(UUID.randomUUID().toString(), input.title.trim(), s, indexes.index(s.indexId).snapshotId, now, now, 0))
    }
    /** Повтор того же requestId возвращает запись; concurrent/stale сообщения не запускают второй флоу. */
    fun send(id: String, input: SendTurn): ConversationDetail {
        require(input.question.isNotBlank() && input.question.length <= 2000 && input.requestId.matches(Regex("[A-Za-z0-9_-]{1,80}")) && input.expectedRevision >= 0)
        val claim = repository.begin(id, input.copy(question = input.question.trim()))
        if (!claim.owned) return claim.detail
        val d = claim.detail; val pending = claim.turn; val s = d.conversation.settings
        val previous = d.turns.filter { it.id != pending.id }
        val recent = selectHistory(previous, s)
        val omitted = previous.size - recent.size
        var prep: PreparationTrace? = null
        var result: GroundedResult? = null
        var memory = d.memory
        var issue: String? = null
        try {
            prep = preparer.prepare(DialogueContext(pending.id, pending.question, d.memory, recent, omitted))
            if (prep.issues.isNotEmpty()) issue = "Подготовка вопроса/памяти не прошла проверку. Память не изменена; генерация не запускалась."
            else {
                memory = prep.memory
                result = grounding.answer(GroundingRequest(pending.question, s.indexId, s.candidateTopK, s.finalTopK, s.similarityThreshold, s.contextMaxCharacters, false, s.maxOutputTokens), GroundingDialogue(prep.query, memory, recent, omitted))
            }
        } catch (failure: Exception) {
            log.warn("Conversation failed chat={} turn={} type={} code={}", id, pending.id, failure.javaClass.simpleName, (failure as? LabException)?.code)
            issue = if (failure is LabException) failure.message else "Ошибка обработки. Вопрос сохранён, автоматического повтора нет."
        }
        val attempts = 1 + (result?.llmStagesAttempted ?: 0)
        val usages = listOfNotNull(prep?.usage, result?.totalUsage)
        val measuredStages = (if (prep?.usage != null) 1 else 0) + (if (result?.totalUsage != null) result.llmStagesAttempted else 0)
        val total = if (attempts == measuredStages) sum(usages) else null
        val parts = listOfNotNull(prep?.estimatedCost, result?.estimatedCost)
        val costKnown = (if (prep?.estimatedCost != null) 1 else 0) + (if (result?.estimatedCost != null) result.llmStagesAttempted else 0)
        val cost = if (costKnown == attempts && parts.isNotEmpty()) parts.first().copy(minimumUsd = parts.sumOf { it.minimumUsd }, maximumUsd = parts.sumOf { it.maximumUsd }, note = "Подготовка + ответ, без двойного учёта. Оценка, не списание.") else null
        return repository.finish(id, pending.copy(status = TurnStatus.COMPLETED, preparation = prep, result = result, issue = issue, memoryAfter = memory, includedHistoryTurnIds = recent.map { it.turnId }, omittedHistoryTurnCount = omitted, totalUsage = total, estimatedCost = cost, llmStagesAttempted = attempts), memory)
    }
    /** Полная история хранится; в модель входят последние целые обмены, выбор виден в каждом turn. */
    private fun selectHistory(turns: List<ConversationTurn>, settings: ConversationSettings): List<DialogueItem> {
        var remaining = settings.historyMaxCharacters
        val selected = mutableListOf<DialogueItem>()
        for (turn in turns.takeLast(settings.historyTurns).asReversed()) {
            val answer = turn.result?.takeIf { it.status in listOf(GroundedStatus.ANSWERED, GroundedStatus.UNKNOWN) }?.let { it.answer + (it.clarification?.let { c -> "\n$c" } ?: "") }
            val size = turn.question.length + (answer?.length ?: 0)
            if (size > remaining) break // Не пропускаем новое сообщение ради старого; не режем текст молча.
            selected.add(DialogueItem(turn.id, turn.question, answer)); remaining -= size
        }
        return selected.asReversed()
    }
    private fun sum(u: List<TokenUsage>) = TokenUsage(u.sumOf { it.promptTokens }, u.sumOf { it.completionTokens }, u.sumOf { it.totalTokens }, if (u.all { it.cacheHitTokens != null }) u.sumOf { it.cacheHitTokens!! } else null, if (u.all { it.cacheMissTokens != null }) u.sumOf { it.cacheMissTokens!! } else null)
}
