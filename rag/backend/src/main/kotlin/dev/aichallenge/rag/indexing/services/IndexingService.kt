package dev.aichallenge.rag.indexing.services

import dev.aichallenge.rag.common.*
import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.documents.services.CorpusService
import dev.aichallenge.rag.embeddings.ports.EmbeddingProvider
import dev.aichallenge.rag.indexing.ports.IndexRepository
import dev.aichallenge.rag.indexing.enums.JobStatus
import dev.aichallenge.rag.indexing.models.*
import dev.aichallenge.rag.retrieval.services.VectorMath
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Оркестрирует один bounded job: разбиение → batch embeddings → проверка → атомарная публикация. */
@Service
class IndexingService(
    private val corpus: CorpusService, private val chunking: ChunkingService,
    private val embeddings: EmbeddingProvider, private val repository: IndexRepository,
    private val properties: RagProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    fun preview(config: ChunkConfig): ChunkPreview = chunking.preview(corpus.snapshot(), config)
    fun start(config: ChunkConfig): IndexJob {
        val preview = preview(config)
        require(properties.batchSize in 1..64)
        if (!busy.compareAndSet(false, true)) throw LabException("indexing_busy", "Уже строится индекс. Дождитесь окончания текущего задания.", HttpStatus.CONFLICT)
        val now = Instant.now().toString()
        val job = IndexJob(UUID.randomUUID().toString(), config, JobStatus.QUEUED, 0, preview.chunks.size, now, now)
        try {
            repository.saveJob(job)
            executor.submit { build(job, preview) }
        } catch (error: Exception) { busy.set(false); throw error }
        return job
    }
    private fun build(initial: IndexJob, preview: ChunkPreview) {
        var job = initial.copy(status = JobStatus.RUNNING, updatedAt = Instant.now().toString())
        val started = System.nanoTime()
        try {
            repository.saveJob(job)
            val identity = embeddings.identity()
            val vectors = mutableListOf<FloatArray>()
            var embeddingMs = 0L
            var inputTokens = 0L
            var usageKnown = true
            preview.chunks.chunked(properties.batchSize).forEach { batch ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val result = embeddings.embed(batch.map { "${it.title}\n${it.section}\n\n${it.text}" })
                require(result.vectors.size == batch.size)
                result.vectors.forEach { require(it.size == identity.dimension); VectorMath.validate(it) }
                vectors.addAll(result.vectors)
                embeddingMs += result.milliseconds
                if (result.inputTokens == null) usageKnown = false else inputTokens += result.inputTokens
                job = job.copy(processed = vectors.size, updatedAt = Instant.now().toString())
                repository.saveJob(job)
                log.info("Index job={} processed={}/{} strategy={}", job.id, job.processed, job.total, job.config.strategy)
            }
            if (embeddings.identity() != identity) throw LabException("model_changed", "Embedding-модель изменилась во время построения. Частичный индекс не опубликован.")
            val info = IndexInfo(UUID.randomUUID().toString(), preview.snapshotId, Instant.now().toString(), preview.config, identity, preview.metrics, (System.nanoTime() - started) / 1_000_000, embeddingMs, if (usageKnown) inputTokens else null, vectors.sumOf { it.size.toLong() * 4 })
            job = job.copy(status = JobStatus.READY, indexId = info.id, updatedAt = Instant.now().toString())
            repository.publish(corpus.snapshot(), info, preview.chunks, vectors, job)
            log.info("Index ready job={} index={} chunks={} dimension={}", job.id, info.id, preview.chunks.size, identity.dimension)
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            log.error("Index job failed id={}", job.id, error)
            repository.saveJob(job.copy(status = JobStatus.FAILED, indexId = null, updatedAt = Instant.now().toString(), error = if (error is LabException) error.message else "Построение не завершено. Готовые индексы сохранены; подробности в логах backend."))
        } finally { busy.set(false) }
    }
    fun compare(ids: List<String>): IndexComparison {
        if (ids.size != 2 || ids.distinct().size != 2) throw LabException("comparison_requires_two", "Выберите два разных готовых индекса.")
        val indexes = ids.map(repository::index)
        val notes = mutableListOf<String>()
        val compatible = indexes.map { it.snapshotId }.distinct().size == 1 && indexes.map { it.embedding }.distinct().size == 1
        if (!compatible) notes.add("Корпус или embedding space отличаются: это не контролируемое сравнение chunking.")
        if (indexes.map { it.config.maxCharacters to it.config.overlapCharacters }.distinct().size != 1) notes.add("Параметры размера/перекрытия отличаются; результат нельзя объяснять только стратегией.")
        if (indexes.map { it.config.strategy }.distinct().size != 2) notes.add("Выбрана одинаковая стратегия; для задания сравните FIXED и STRUCTURAL.")
        notes.add("Similarity — cosine, не вероятность правильного ответа. Результаты поиска оцениваются по ожидаемым разделам.")
        notes.add("Время включает загрузку/прогрев модели и зависит от порядка запуска; скорость не доказывает качество.")
        return IndexComparison(compatible, notes, indexes)
    }
    @PreDestroy
    fun shutdown() { executor.shutdownNow() }
}
