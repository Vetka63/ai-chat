package dev.aichallenge.rag.indexing.models

import dev.aichallenge.rag.indexing.enums.*
import jakarta.validation.constraints.*

/** Размеры в символах UTF-16 канонического текста, не в токенах модели. */
data class ChunkConfig(
    val strategy: ChunkStrategy = ChunkStrategy.FIXED,
    @field:Min(300) @field:Max(10000) val maxCharacters: Int = 3000,
    @field:Min(0) @field:Max(3000) val overlapCharacters: Int = 300,
)

/** Чанк хранит точный substring документа и диапазоны; перекрытие не создаёт новый исходный текст. */
data class Chunk(
    val chunkId: String, val documentId: String, val source: String, val title: String, val section: String,
    val sections: List<String>, val ordinal: Int, val start: Int, val endExclusive: Int,
    val sourceLineStart: Int, val sourceLineEnd: Int, val text: String, val textSha256: String,
)

/** Измерения разбиения, а не утверждение о качестве будущих ответов LLM. */
data class ChunkMetrics(
    val chunkCount: Int, val minCharacters: Int, val medianCharacters: Int, val p95Characters: Int,
    val maxCharacters: Int, val uniqueCharacters: Int, val indexedCharacters: Long,
    val coveragePercent: Double, val crossSectionChunks: Int, val splitCodeBlocks: Int,
)

/** Сохранённая идентичность runtime модели исключает поиск запросом от другого embedding space. */
data class EmbeddingIdentity(val model: String, val digest: String, val dimension: Int, val templateVersion: String = "progit-heading-v1")

/** Полностью опубликованная версия индекса и фактические расходы локального embedding runtime. */
data class IndexInfo(
    val id: String, val snapshotId: String, val createdAt: String, val config: ChunkConfig,
    val embedding: EmbeddingIdentity, val metrics: ChunkMetrics, val buildMilliseconds: Long,
    val embeddingMilliseconds: Long, val embeddingInputTokens: Long?, val vectorBytes: Long,
)

/** Прогресс задания хранится в SQLite, чтобы UI мог опрашивать его и после перезагрузки страницы. */
data class IndexJob(
    val id: String, val config: ChunkConfig, val status: JobStatus, val processed: Int, val total: Int,
    val createdAt: String, val updatedAt: String, val indexId: String? = null, val error: String? = null,
)

/** Preview позволяет исследовать разбиение даже без установленной embedding-модели. */
data class ChunkPreview(val snapshotId: String, val config: ChunkConfig, val metrics: ChunkMetrics, val chunks: List<Chunk>)

/** Пара подготовленных индексов; поиск выполняется по каждому отдельно. */
data class CompareRequest(val indexIds: List<String>)

/** Сравнение разрешено только при одинаковом корпусе и embedding space. */
data class IndexComparison(val comparable: Boolean, val notes: List<String>, val indexes: List<IndexInfo>)
