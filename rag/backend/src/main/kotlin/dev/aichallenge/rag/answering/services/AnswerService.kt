package dev.aichallenge.rag.answering.services

import dev.aichallenge.rag.answering.enums.AnswerMode
import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.answering.ports.LlmClient
import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.indexing.ports.IndexRepository
import dev.aichallenge.rag.retrieval.models.SearchRequest
import dev.aichallenge.rag.retrieval.services.SearchService
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Оркестратор одного ответа; не меняет индекс и не сохраняет историю будущего чата. */
@Service
class AnswerService(private val search: SearchService, private val repository: IndexRepository, private val llm: LlmClient, private val assembler: PromptAssembler, private val costs: CostEstimator) {
    fun settings() = llm.settings()
    fun answer(request: AnswerRequest): AnswerResult {
        val started = System.nanoTime()
        val question = request.question.trim()
        if (question.isBlank()) throw LabException("empty_question", "Введите вопрос.")
        val context = if (request.mode == AnswerMode.RAG) {
            val indexId = request.indexId?.takeIf { it.isNotBlank() } ?: throw LabException("index_required", "Выберите готовый индекс для RAG.")
            val index = repository.index(indexId)
            val found = search.search(indexId, SearchRequest(question, request.topK))
            val selected = assembler.select(found.hits, request.contextMaxCharacters)
            val includedIds = selected.map { it.chunk.chunkId }.toSet()
            AnswerContext(indexId, index.snapshotId, found.hits.size, selected, found.hits.filter { it.chunk.chunkId !in includedIds }.map { it.chunk.chunkId }, selected.sumOf { it.chunk.text.length }, request.contextMaxCharacters, found.milliseconds, found.inputTokens)
        } else AnswerContext(null, null, 0, emptyList(), emptyList(), 0, request.contextMaxCharacters, 0, null)
        val messages = assembler.assemble(question, request.mode, context.included)
        val completion = llm.complete(messages, request.maxOutputTokens)
        val warnings = mutableListOf("Источники ниже — переданные фрагменты, не проверенные цитаты ответа. Проверка цитат появится в дне 24.")
        if (completion.finishReason == "length") warnings.add("Провайдер завершил ответ по лимиту: текст может быть неполным.")
        if (completion.usage == null) warnings.add("API не предоставил usage: точный расход и стоимость неизвестны.")
        if (context.omittedChunkIds.isNotEmpty()) warnings.add("Часть найденных чанков исключена из-за бюджета. Частичные чанки не отправлялись.")
        return AnswerResult(UUID.randomUUID().toString(), Instant.now().toString(), question, request.mode, completion.model, llm.settings().temperature, llm.settings().thinking, completion.content, completion.finishReason, completion.finishReason == "length", request.maxOutputTokens, (System.nanoTime() - started) / 1_000_000, completion.milliseconds, completion.usage, costs.estimate(completion.model, completion.usage), context, messages, warnings)
    }
}
