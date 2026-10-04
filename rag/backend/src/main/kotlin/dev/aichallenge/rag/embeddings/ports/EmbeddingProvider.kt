package dev.aichallenge.rag.embeddings.ports

import dev.aichallenge.rag.indexing.models.EmbeddingIdentity

/** Результат локального batch; usage берётся у runtime, не оценивается по длине строки. */
data class EmbeddingBatch(val vectors: List<FloatArray>, val inputTokens: Long?, val milliseconds: Long)

/** Контракт отделяет индексацию от HTTP-протокола Ollama и выбранной модели. */
interface EmbeddingProvider {
    fun identity(): EmbeddingIdentity
    fun embed(texts: List<String>): EmbeddingBatch
}
