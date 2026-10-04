package dev.aichallenge.rag.retrieval.services

import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.embeddings.ports.EmbeddingProvider
import dev.aichallenge.rag.indexing.ports.IndexRepository
import dev.aichallenge.rag.retrieval.models.*
import org.springframework.stereotype.Service

/** Диагностический retrieval: embedding вопроса → exact cosine → top-K; генерации здесь нет. */
@Service
class SearchService(private val repository: IndexRepository, private val embeddings: EmbeddingProvider) {
    fun search(indexId: String, request: SearchRequest): SearchResult {
        val index = repository.index(indexId)
        val started = System.nanoTime()
        val identity = embeddings.identity()
        if (index.embedding != identity) throw LabException("embedding_space_mismatch", "Модель/digest/размерность отличаются от сохранённого индекса. Используйте прежнюю модель или постройте новый индекс.")
        val result = embeddings.embed(listOf("Instruct: Найди фрагменты русской книги Pro Git, отвечающие на вопрос.\nQuery: ${request.query}"))
        val query = result.vectors.single()
        require(query.size == identity.dimension)
        val hits = repository.vectors(indexId).map { (chunk, vector) -> chunk to VectorMath.cosine(query, vector) }
            .sortedWith(compareByDescending<Pair<dev.aichallenge.rag.indexing.models.Chunk, Double>> { it.second }.thenBy { it.first.chunkId })
            .take(request.topK).mapIndexed { i, (chunk, score) -> SearchHit(i + 1, score, chunk) }
        return SearchResult(indexId, request.query, (System.nanoTime() - started) / 1_000_000, result.inputTokens, hits)
    }
}
