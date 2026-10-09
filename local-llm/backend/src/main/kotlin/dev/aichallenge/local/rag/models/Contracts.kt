package dev.aichallenge.local.rag.models

import dev.aichallenge.local.generation.RunStatus
import jakarta.validation.constraints.*

/** Режим выбирается для каждого независимого вопроса; автоматического fallback нет. */
enum class RagMode { LOCAL, DEEPSEEK, COMPARE }
enum class IndexStatus { BUILDING, READY, FAILED, INTERRUPTED }
enum class ExperimentStatus { PREPARING, RUNNING, COMPLETED, PARTIAL, FAILED, UNKNOWN, INTERRUPTED }
enum class CitationStatus { VALID, MISSING, INVALID, NOT_APPLICABLE }

/** Нормализованный UTF-8 документ; text не входит в краткий список документов. */
data class RagDocument(val id: String, val title: String, val filename: String, val sha256: String, val text: String, val createdAt: String)
data class DocumentInfo(val id: String, val title: String, val filename: String, val sha256: String, val characters: Int, val createdAt: String)
data class Chunk(val id: String, val documentId: String, val source: String, val title: String, val section: String,
    val ordinal: Int, val start: Int, val endExclusive: Int, val text: String)
data class VectorChunk(val chunk: Chunk, val vector: List<Double>)
/** Точное пространство поиска: имя, digest, размерность, версии шаблонов. */
data class EmbeddingSpace(val model: String, val digest: String, val dimension: Int, val template: String = "warhammer-ru-v1")
data class IndexRequest(val documentIds: List<String> = emptyList(), @field:Min(600) @field:Max(3000) val chunkCharacters: Int = 1400,
    @field:Min(0) @field:Max(500) val overlapCharacters: Int = 180)
data class RagIndex(val id: String, val createdAt: String, val request: IndexRequest, val documentIds: List<String>,
    val status: IndexStatus, val processed: Int = 0, val total: Int = 0, val space: EmbeddingSpace? = null,
    val milliseconds: Long? = null, val error: String? = null)
data class SearchHit(val ref: String, val similarity: Double, val chunk: Chunk, val denseRank: Int? = null, val lexicalRank: Int? = null, val fusionScore: Double? = null, val origin: String = "HYBRID")
/** Один сохранённый контекст для обеих моделей, включая кандидаты и общий prompt hash. */
data class RetrievalSnapshot(val hits: List<SearchHit>, val included: List<SearchHit>, val milliseconds: Long,
    val embeddingTokens: Long?, val contextCharacters: Int, val promptSha256: String, val embeddingCacheHit: Boolean = false)
/** Кешируется только embedding вопроса, не retrieval, ответ или ожидания evaluation. */
data class CachedQueryEmbedding(val vector: List<Double>)
data class RagRequest(@field:NotBlank val requestId: String, @field:NotBlank val indexId: String,
    @field:NotBlank @field:Size(max = 2000) val question: String, val mode: RagMode = RagMode.LOCAL,
    val localModel: String = "qwen3:8b", val cloudModel: String = "deepseek-flash",
    @field:Min(1) @field:Max(12) val topK: Int = 5,
    @field:DecimalMin("0.0") @field:DecimalMax("1.0") val threshold: Double = 0.35,
    @field:Min(1500) @field:Max(12000) val contextCharacters: Int = 7000,
    @field:DecimalMin("0.0") @field:DecimalMax("2.0") val temperature: Double = 0.0,
    val thinking: Boolean = false, @field:Min(1) @field:Max(8192) val maxOutputTokens: Int? = null,
    val documentIds: List<String> = emptyList())
data class ModelAnswer(val provider: String, val requestedModel: String, val actualModel: String? = null,
    val status: RunStatus = RunStatus.RUNNING, val startedAt: String? = null, val finishedAt: String? = null,
    val answer: String? = null, val milliseconds: Long? = null, val inputTokens: Long? = null, val outputTokens: Long? = null,
    val loadMilliseconds: Double? = null, val tokensPerSecond: Double? = null, val finishReason: String? = null,
    val citationStatus: CitationStatus = CitationStatus.NOT_APPLICABLE, val citedRefs: List<String> = emptyList(), val error: String? = null,
    val thinking: String? = null)
data class RagExperiment(val id: String, val createdAt: String, val request: RagRequest,
    val status: ExperimentStatus = ExperimentStatus.PREPARING, val retrieval: RetrievalSnapshot? = null,
    val answers: List<ModelAnswer> = emptyList(), val milliseconds: Long? = null, val error: String? = null, val conclusion: String? = null)
data class ConclusionRequest(@field:Size(max = 3000) val text: String)
data class ExperimentInfo(val id: String, val createdAt: String, val question: String, val mode: RagMode, val status: ExperimentStatus)
data class RagSettings(val embeddingModel: String, val cloudEnabled: Boolean, val cloudReady: Boolean, val cloudModels: List<String>, val contextTokens: Int)
class RagFailure(message: String) : RuntimeException(message)
