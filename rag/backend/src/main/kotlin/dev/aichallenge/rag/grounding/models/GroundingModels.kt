package dev.aichallenge.rag.grounding.models

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.grounding.enums.GroundedStatus
import dev.aichallenge.rag.retrieval.models.SearchHit
import dev.aichallenge.rag.retrieval.selection.models.CandidateDecision
import dev.aichallenge.rag.rewriting.models.RewriteTrace
import jakarta.validation.constraints.*
import dev.aichallenge.rag.taskmemory.models.DialogueItem
import dev.aichallenge.rag.taskmemory.models.TaskMemory

/** Внутренний контекст чата; history/memory объясняют вопрос, но не заменяют book evidence. */
data class GroundingDialogue(val resolvedQuestion: String, val memory: TaskMemory, val recent: List<DialogueItem>, val omittedTurnCount: Int)

/** Один grounded-вопрос; threshold обязателен, rewrite опционален, история не подмешивается. */
data class GroundingRequest(
    @field:NotBlank @field:Size(max = 2000) val question: String,
    @field:NotBlank @field:Size(max = 128) val indexId: String,
    @field:Min(1) @field:Max(20) val candidateTopK: Int = 20,
    @field:Min(1) @field:Max(10) val finalTopK: Int = 10,
    @field:DecimalMin("-1") @field:DecimalMax("1") val similarityThreshold: Double = .60,
    @field:Min(300) @field:Max(60000) val contextMaxCharacters: Int = 32000,
    val useRewrite: Boolean = false,
    @field:Min(1) @field:Max(32768) val maxOutputTokens: Int? = null,
)

/** Metadata берётся сервером из фактически переданного чанка, не из ответа модели. */
data class EvidenceSource(val chunkId: String, val documentId: String, val source: String, val title: String, val section: String)

/** Цитата — точный substring; координаты UTF-16 указывают на snapshot документа выбранного индекса. */
data class VerifiedCitation(val source: EvidenceSource, val quote: String, val startInChunk: Int, val endInChunkExclusive: Int, val canonicalStart: Int, val canonicalEndExclusive: Int)

/** Единственный публичный текст ответа строится из этих пунктов, каждый имеет хотя бы одну цитату. */
data class GroundedClaim(val text: String, val citations: List<VerifiedCitation>)

/** Безопасная диагностическая причина отказа; не содержит ключей и недоверенного ответа модели. */
data class EvidenceIssue(val code: String, val message: String, val claimIndex: Int? = null, val citationIndex: Int? = null)

/** Результат строгой проверки: при ошибке публичных claims/sources нет, частичный ответ не выпускается. */
data class EvidenceValidation(val status: GroundedStatus, val claims: List<GroundedClaim> = emptyList(), val sources: List<EvidenceSource> = emptyList(), val clarification: String? = null, val issues: List<EvidenceIssue> = emptyList())

/** Поиск, отбор и упаковка разделены; видно, что не прошло threshold и что не поместилось. */
data class GroundingRetrieval(val searchQuery: String, val rawCandidates: List<SearchHit>, val selectedCandidates: List<SearchHit>, val decisions: List<CandidateDecision>, val included: List<SearchHit>, val omittedChunkIds: List<String>, val textCharacters: Int, val milliseconds: Long, val embeddingInputTokens: Long?)

/** Raw JSON доступен только как явно непроверенная диагностика, никогда как основной ответ. */
data class GroundingGeneration(val model: String, val finishReason: String, val milliseconds: Long, val usage: TokenUsage?, val estimatedCost: CostEstimate?, val messages: List<LlmMessage>, val rawJson: String)

/** Единственный отклонённый черновик и его проверка; диагностические данные, не публичные claims. */
data class GroundingRepair(val originalGeneration: GroundingGeneration, val originalSupportCheck: ClaimSupportCheck)

/** Результат независимого вопроса; UNKNOWN/INVALID/ERROR не маскируются как доказанный ответ. */
data class GroundedResult(val request: GroundingRequest, val snapshotId: String, val status: GroundedStatus, val answer: String, val clarification: String?, val claims: List<GroundedClaim>, val sources: List<EvidenceSource>, val issues: List<EvidenceIssue>, val retrieval: GroundingRetrieval?, val rewrite: RewriteTrace?, val generation: GroundingGeneration?, val llmStagesAttempted: Int, val totalUsage: TokenUsage?, val estimatedCost: CostEstimate?, val totalMilliseconds: Long, val warnings: List<String>, val supportCheck: ClaimSupportCheck? = null, val repair: GroundingRepair? = null)
