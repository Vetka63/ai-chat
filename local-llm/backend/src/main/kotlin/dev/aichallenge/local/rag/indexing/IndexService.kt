package dev.aichallenge.local.rag.indexing

import dev.aichallenge.local.generation.GenerationGate
import dev.aichallenge.local.rag.config.RagProperties
import dev.aichallenge.local.rag.embeddings.EmbeddingEngine
import dev.aichallenge.local.rag.models.*
import dev.aichallenge.local.rag.storage.RagRepository
import org.springframework.stereotype.Service
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Индексация в фоне; общий GPU gate предотвращает конкуренцию с чатами/генерацией. */
@Service
class IndexService(private val repository: RagRepository, private val chunker: ParagraphChunker,
    private val embeddings: EmbeddingEngine, private val config: RagProperties, private val gate: GenerationGate) {
    private val active = AtomicBoolean(false)
    private val worker = Executors.newVirtualThreadPerTaskExecutor()
    fun start(request: IndexRequest): RagIndex {
        if (request.chunkCharacters !in 600..3000 || request.overlapCharacters !in 0..500 || request.overlapCharacters >= request.chunkCharacters / 2) throw RagFailure("Overlap должен быть меньше половины чанка.")
        val documents = if (request.documentIds.isEmpty()) repository.documents() else request.documentIds.distinct().map(repository::document)
        if (documents.isEmpty()) throw RagFailure("Сначала загрузите хотя бы один документ.")
        if (!active.compareAndSet(false, true)) throw RagFailure("Индексация уже выполняется.")
        val initial = RagIndex(UUID.randomUUID().toString(), Instant.now().toString(), request, documents.map { it.id }, IndexStatus.BUILDING)
        try { repository.saveIndex(initial) } catch (failure: Exception) { active.set(false); throw failure }
        worker.submit {
            val started = System.nanoTime()
            try { gate.exclusive {
                val digest = embeddings.digest()
                val chunks = documents.flatMap { chunker.chunk(it, request.chunkCharacters, request.overlapCharacters) }
                repository.saveIndex(initial.copy(total = chunks.size))
                val vectors = mutableListOf<VectorChunk>()
                var dimension: Int? = null
                chunks.chunked(8).forEach { batch ->
                    val encoded = embeddings.embed(batch.map { "${it.title}\n${it.section}\n${it.text}" })
                    if (encoded.vectors.size != batch.size) throw RagFailure("Неверное число векторов в batch.")
                    batch.zip(encoded.vectors).forEach { (chunk, vector) ->
                        dev.aichallenge.local.rag.embeddings.validateVector(vector)
                        if (dimension != null && dimension != vector.size) throw RagFailure("Размерность изменилась при индексации.")
                        dimension = vector.size; vectors.add(VectorChunk(chunk, vector))
                    }
                    repository.saveIndex(initial.copy(total = chunks.size, processed = vectors.size))
                }
                if (embeddings.digest() != digest) throw RagFailure("Embedding-модель изменилась. Создайте новый индекс.")
                repository.publish(initial.copy(status = IndexStatus.READY, processed = vectors.size, total = vectors.size,
                    space = EmbeddingSpace(config.embeddingModel, digest, requireNotNull(dimension)), milliseconds = (System.nanoTime() - started) / 1_000_000), vectors)
            } } catch (failure: Exception) {
                repository.saveIndex(repository.index(initial.id).copy(status = IndexStatus.FAILED, error = failure.message?.take(250) ?: "Ошибка индексации."))
            } finally { active.set(false) }
        }
        return initial
    }
    @PreDestroy fun close() { worker.shutdownNow() }
}
