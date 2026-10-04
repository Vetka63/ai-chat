package dev.aichallenge.rag.experiments.models

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.experiments.enums.*
import dev.aichallenge.rag.retrieval.models.SearchHit
import dev.aichallenge.rag.retrieval.selection.models.CandidateDecision
import dev.aichallenge.rag.rewriting.models.RewriteTrace
import jakarta.validation.constraints.*

/** Один снимок настроек на все режимы. Поиск без генерации может всё равно оплатить один rewrite. */
data class ExperimentRequest(
    @field:NotBlank @field:Size(max = 2000) val question: String,
    @field:NotBlank @field:Size(max = 128) val indexId: String,
    @field:Size(min = 1, max = 4) val modes: List<RetrievalMode> = listOf(RetrievalMode.RAW, RetrievalMode.FILTERED, RetrievalMode.REWRITE_FILTERED),
    @field:Min(1) @field:Max(20) val candidateTopK: Int = 10,
    @field:Min(1) @field:Max(10) val finalTopK: Int = 5,
    @field:DecimalMin("-1.0") @field:DecimalMax("1.0") val similarityThreshold: Double = 0.65,
    @field:Min(300) @field:Max(60000) val contextMaxCharacters: Int = 16000,
    @field:Min(1) @field:Max(32768) val maxOutputTokens: Int? = null,
    val generateAnswers: Boolean = true,
)
/** Поиск и отбор видны отдельно от упаковки в бюджет, которая остаётся в AnswerContext. */
data class RetrievalTrace(val originalQuestion: String, val searchQuery: String, val filterApplied: Boolean, val thresholdApplied: Double?, val rawCandidates: List<SearchHit>, val selectedCandidates: List<SearchHit>, val decisions: List<CandidateDecision>, val retrievalMilliseconds: Long, val embeddingInputTokens: Long?)
/** Безопасная ошибка конкретной стадии, без raw provider body и секретов. */
data class ExperimentError(val code: String, val message: String)
/** Частичный сбой одного режима не уничтожает успешные результаты остальных. */
data class ExperimentResult(val mode: RetrievalMode, val status: ExperimentStatus, val message: String?, val error: ExperimentError?, val pipeline: RetrievalTrace?, val answer: AnswerResult?, val generationAttempted: Boolean)
/** Общий rewrite и общие candidate pools делают сравнение воспроизводимее; токены rewrite учитываются один раз. */
data class ExperimentComparison(val request: ExperimentRequest, val snapshotId: String, val rewrite: RewriteTrace?, val rewriteError: ExperimentError?, val results: List<ExperimentResult>, val totalMilliseconds: Long, val llmStagesAttempted: Int, val totalUsage: TokenUsage?, val estimatedCost: CostEstimate?, val warnings: List<String>)
