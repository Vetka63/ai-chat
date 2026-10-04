package dev.aichallenge.rag.answering.services

import dev.aichallenge.rag.answering.enums.AnswerMode
import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.answering.ports.LlmClient
import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.indexing.ports.IndexRepository
import dev.aichallenge.rag.retrieval.models.SearchRequest
import dev.aichallenge.rag.retrieval.services.SearchService
import org.springframework.stereotype.Service

/** Оркестратор одного ответа; не меняет индекс и не сохраняет историю будущего чата. */
@Service
class AnswerService(private val search: SearchService, private val repository: IndexRepository, private val llm: LlmClient, private val assembler: PromptAssembler, private val generator: AnswerGenerator) {
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
        return generator.generate(request, context, started)
    }
}
