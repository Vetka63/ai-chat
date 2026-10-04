package dev.aichallenge.rag.grounding.services

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.answering.ports.LlmClient
import dev.aichallenge.rag.answering.services.CostEstimator
import dev.aichallenge.rag.answering.services.PromptAssembler
import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.grounding.enums.GroundedStatus
import dev.aichallenge.rag.grounding.enums.SupportCheckStatus
import dev.aichallenge.rag.grounding.models.*
import dev.aichallenge.rag.grounding.ports.CitationValidator
import dev.aichallenge.rag.grounding.ports.ClaimSupportValidator
import dev.aichallenge.rag.indexing.ports.IndexRepository
import dev.aichallenge.rag.retrieval.models.SearchRequest
import dev.aichallenge.rag.retrieval.services.SearchService
import dev.aichallenge.rag.retrieval.selection.ports.CandidateSelector
import dev.aichallenge.rag.rewriting.models.RewriteTrace
import dev.aichallenge.rag.rewriting.ports.QueryRewriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Поток ответа: поиск → порог → JSON → точность цитат → поддержка смысла → публичный ответ. */
@Service
class GroundingService(private val repository: IndexRepository, private val search: SearchService, private val selector: CandidateSelector, private val rewriter: QueryRewriter, private val packing: PromptAssembler, private val prompt: GroundingPromptAssembler, private val llm: LlmClient, private val validator: CitationValidator, private val costs: CostEstimator, private val supportValidator: ClaimSupportValidator) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Пустой контекст не вызывает LLM; неверные цитаты не выпускают частичный ответ и не запускают retry. */
    fun answer(request: GroundingRequest, dialogue: GroundingDialogue? = null): GroundedResult {
        validate(request)
        val clean = request.copy(question = request.question.trim())
        val snapshotId = repository.index(clean.indexId).snapshotId
        val started = System.nanoTime()
        var rewrite: RewriteTrace? = null
        var retrieval: GroundingRetrieval? = null
        var generation: GroundingGeneration? = null
        var supportCheck: ClaimSupportCheck? = null
        var attempts = 0
        fun result(checked: EvidenceValidation): GroundedResult {
            val usages = listOfNotNull(rewrite?.usage, generation?.usage, supportCheck?.generation?.usage)
            val complete = attempts > 0 && usages.size == attempts
            val costParts = listOfNotNull(rewrite?.estimatedCost, generation?.estimatedCost, supportCheck?.generation?.estimatedCost)
            val cost = if (attempts > 0 && costParts.size == attempts) costParts.first().copy(minimumUsd = costParts.sumOf { it.minimumUsd }, maximumUsd = costParts.sumOf { it.maximumUsd }, note = "Сумма стадий, rewrite один раз; не фактическое списание.") else null
            val answer = when (checked.status) {
                GroundedStatus.ANSWERED -> checked.claims.joinToString("\n\n") { it.text }
                GroundedStatus.UNKNOWN -> "Не знаю по найденным материалам."
                GroundedStatus.INVALID_EVIDENCE -> "Ответ не прошёл проверку источников, цитат или смысловой поддержки. Непроверенные утверждения не показаны."
                GroundedStatus.ERROR -> "Не удалось подготовить ответ."
            }
            val warnings = mutableListOf("Точные цитаты и отдельная модельная проверка смысла снижают риск неподтверждённых выводов, но не гарантируют правильность книги или безошибочность проверки.", "Cosine threshold — настройка поиска, не вероятность уверенности.")
            if (attempts > 0 && !complete) warnings.add("Полный API usage неизвестен; доступные измерения отдельных стадий сохранены.")
            return GroundedResult(clean, snapshotId, checked.status, answer, checked.clarification, checked.claims, checked.sources, checked.issues, retrieval, rewrite, generation, attempts, if (complete) sumUsage(usages) else null, cost, (System.nanoTime() - started) / 1_000_000, warnings, supportCheck)
        }
        try {
            val query = dialogue?.resolvedQuestion ?: if (clean.useRewrite) { attempts++; rewriter.rewrite(clean.question).also { rewrite = it }.query } else clean.question
            val pool = search.search(clean.indexId, SearchRequest(query, clean.candidateTopK))
            val selection = selector.select(pool.hits, clean.finalTopK, clean.similarityThreshold)
            val included = packing.select(selection.selected, clean.contextMaxCharacters)
            val ids = included.map { it.chunk.chunkId }.toSet()
            retrieval = GroundingRetrieval(query, pool.hits, selection.selected, selection.decisions, included, selection.selected.filter { it.chunk.chunkId !in ids }.map { it.chunk.chunkId }, included.sumOf { it.chunk.text.length }, pool.milliseconds, pool.inputTokens)
            if (selection.selected.isEmpty()) return result(EvidenceValidation(GroundedStatus.UNKNOWN, clarification = "Уточните вопрос о Git или выберите другой индекс. Поиск не нашёл фрагментов выше выбранного порога."))
            if (included.isEmpty()) return result(EvidenceValidation(GroundedStatus.ERROR, issues = listOf(EvidenceIssue("context_budget_too_small", "Целые чанки не помещаются в бюджет. Увеличьте его; LLM не вызывалась."))))
            val messages = prompt.assemble(clean.question, included, dialogue)
            attempts++
            val response = llm.completeJson(messages, clean.maxOutputTokens)
            generation = GroundingGeneration(response.model, response.finishReason, response.milliseconds, response.usage, costs.estimate(response.model, response.usage), messages, response.content)
            var checked = validator.validate(response.content, response.finishReason, included)
            if (checked.status == GroundedStatus.ANSWERED) {
                attempts++
                val support = supportValidator.validate(checked.claims, included)
                supportCheck = support
                if (support.status != SupportCheckStatus.PASSED) checked = EvidenceValidation(GroundedStatus.INVALID_EVIDENCE, issues = support.issues)
            }
            log.info("Grounding completed status={} sources={} claims={} issueCodes={}", checked.status, checked.sources.size, checked.claims.size, checked.issues.map { it.code })
            return result(checked)
        } catch (failure: Exception) {
            val issue = if (failure is LabException) EvidenceIssue(failure.code, failure.message ?: "Ошибка стадии.") else EvidenceIssue("grounding_stage_failed", "Ошибка стадии. Автоматический повтор не выполнялся.")
            log.warn("Grounding failed type={} code={}", failure.javaClass.simpleName, issue.code)
            return result(EvidenceValidation(GroundedStatus.ERROR, issues = listOf(issue)))
        }
    }
    /** Повторяет межполевую проверку для внутренних вызовов без Bean Validation. */
    private fun validate(r: GroundingRequest) {
        if (r.question.isBlank() || r.question.length > 2000 || r.indexId.isBlank() || r.indexId.length > 128 || r.candidateTopK !in 1..20 || r.finalTopK !in 1..10 || r.finalTopK > r.candidateTopK || !r.similarityThreshold.isFinite() || r.similarityThreshold !in -1.0..1.0 || r.contextMaxCharacters !in 300..60000 || r.maxOutputTokens != null && r.maxOutputTokens !in 1..32768) throw LabException("invalid_grounding_request", "Проверьте вопрос, индекс, threshold, K и бюджет. Final K не может превышать candidate K.")
    }
    /** Складывает только известный API usage, не локальные embedding-токены. */
    private fun sumUsage(u: List<TokenUsage>) = TokenUsage(u.sumOf { it.promptTokens }, u.sumOf { it.completionTokens }, u.sumOf { it.totalTokens }, if (u.all { it.cacheHitTokens != null }) u.sumOf { it.cacheHitTokens!! } else null, if (u.all { it.cacheMissTokens != null }) u.sumOf { it.cacheMissTokens!! } else null)
}
