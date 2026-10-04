package dev.aichallenge.rag.experiments.services

import dev.aichallenge.rag.answering.enums.AnswerMode
import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.answering.services.*
import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.experiments.enums.*
import dev.aichallenge.rag.experiments.models.*
import dev.aichallenge.rag.indexing.ports.IndexRepository
import dev.aichallenge.rag.retrieval.models.*
import dev.aichallenge.rag.retrieval.services.SearchService
import dev.aichallenge.rag.retrieval.selection.ports.CandidateSelector
import dev.aichallenge.rag.rewriting.ports.QueryRewriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Оркестратор сравнения, не индексатор и не judge: общие запросы поиска, явные стадии и независимые ошибки. */
@Service
class ExperimentService(private val repository: IndexRepository, private val search: SearchService, private val rewriter: QueryRewriter, private val selector: CandidateSelector, private val assembler: PromptAssembler, private val generator: AnswerGenerator) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Проверяет конфигурацию до платного rewrite; на один эксперимент максимум один rewrite и четыре ответа. */
    fun compare(request: ExperimentRequest): ExperimentComparison {
        validate(request)
        val started = System.nanoTime()
        val clean = request.copy(question = request.question.trim())
        val index = repository.index(clean.indexId)
        val rewriteAttempted = clean.modes.any { it.rewrite }
        val rewritten = if (rewriteAttempted) runCatching { rewriter.rewrite(clean.question) } else null
        val originalPool = if (clean.modes.any { !it.rewrite }) runCatching { search.search(clean.indexId, SearchRequest(clean.question, clean.candidateTopK)) } else null
        val rewrittenPool = rewritten?.getOrNull()?.let { runCatching { search.search(clean.indexId, SearchRequest(it.query, clean.candidateTopK)) } }
        // Четыре режима максимум; отдельные виртуальные потоки не блокируют друг друга при ошибке/timeout LLM.
        val results = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            clean.modes.map { mode -> CompletableFuture.supplyAsync({
                val pool = if (mode.rewrite) rewrittenPool else originalPool
                val failure = if (mode.rewrite) rewritten?.exceptionOrNull() ?: pool?.exceptionOrNull() else pool?.exceptionOrNull()
                if (failure != null) error(mode, failure)
                else runMode(clean, mode, pool!!.getOrThrow(), index.snapshotId)
            }, executor) }.map { it.join() }
        }
        val rewrite = rewritten?.getOrNull()
        val rewriteError = rewritten?.exceptionOrNull()?.let { safeError(it) }
        val attempted = results.count { it.generationAttempted } + if (rewriteAttempted) 1 else 0
        val usages = listOfNotNull(rewrite?.usage) + results.mapNotNull { it.answer?.usage }
        val completeUsage = attempted > 0 && usages.size == attempted
        val costParts = listOfNotNull(rewrite?.estimatedCost) + results.mapNotNull { it.answer?.estimatedCost }
        val totalCost = if (attempted > 0 && costParts.size == attempted) costParts.first().copy(minimumUsd = costParts.sumOf { it.minimumUsd }, maximumUsd = costParts.sumOf { it.maximumUsd }, note = "Сумма оценок завершённых LLM-стадий; общий rewrite учтён один раз, не списание со счёта.") else null
        val warnings = mutableListOf("Cosine — не вероятность правильности. Порог требует подбора для этого корпуса и embedding-модели.", "Это similarity-фильтр, не обученный reranker. Проверки цитат и semantic grounding относятся к дню 24.")
        if (attempted > 0 && !completeUsage) warnings.add("Не все LLM-стадии вернули usage: полный расход/стоимость неизвестны; отдельные успешные измерения сохранены.")
        return ExperimentComparison(clean, index.snapshotId, rewrite, rewriteError, results, (System.nanoTime() - started) / 1_000_000, attempted, if (completeUsage) sumUsage(usages) else null, totalCost, warnings)
    }

    /** Сначала порог и final top-K, затем бюджет целых чанков; исходный вопрос передаётся генератору неизменённым. */
    private fun runMode(request: ExperimentRequest, mode: RetrievalMode, pool: SearchResult, snapshotId: String): ExperimentResult {
        val selection = selector.select(pool.hits, request.finalTopK, request.similarityThreshold.takeIf { mode.filter })
        val trace = RetrievalTrace(request.question, pool.query, mode.filter, request.similarityThreshold.takeIf { mode.filter }, pool.hits, selection.selected, selection.decisions, pool.milliseconds, pool.inputTokens)
        if (selection.selected.isEmpty()) return ExperimentResult(mode, ExperimentStatus.NO_CONTEXT, "После отбора не осталось фрагментов. Ответ LLM не запрашивался. Уменьшите порог или уточните вопрос; это не доказательство отсутствия сведений в книге.", null, trace, null, false)
        if (!request.generateAnswers) return ExperimentResult(mode, ExperimentStatus.RETRIEVED, "Выполнен только поиск и отбор, без генерации ответа.", null, trace, null, false)
        val started = System.nanoTime()
        val packed = assembler.select(selection.selected, request.contextMaxCharacters)
        if (packed.isEmpty()) return ExperimentResult(mode, ExperimentStatus.ERROR, null, ExperimentError("context_budget_too_small", "Отобранные целые чанки не помещаются в бюджет. LLM не вызывалась."), trace, null, false)
        val packedIds = packed.map { it.chunk.chunkId }.toSet()
        val context = AnswerContext(request.indexId, snapshotId, pool.hits.size, packed, selection.selected.filter { it.chunk.chunkId !in packedIds }.map { it.chunk.chunkId }, packed.sumOf { it.chunk.text.length }, request.contextMaxCharacters, pool.milliseconds, pool.inputTokens)
        return try {
            val answer = generator.generate(AnswerRequest(request.question, AnswerMode.RAG, request.indexId, request.finalTopK, request.contextMaxCharacters, request.maxOutputTokens), context, started)
            ExperimentResult(mode, ExperimentStatus.ANSWERED, null, null, trace, answer, true)
        } catch (failure: Exception) { ExperimentResult(mode, ExperimentStatus.ERROR, null, safeError(failure), trace, null, true) }
    }

    /** Межполевые и защитные проверки повторяются даже для внутреннего вызова сервиса без контроллера. */
    private fun validate(request: ExperimentRequest) {
        if (request.question.isBlank() || request.question.length > 2000 || request.indexId.isBlank() || request.modes.isEmpty() || request.modes.size > 4 || request.modes.distinct().size != request.modes.size || request.candidateTopK !in 1..20 || request.finalTopK !in 1..10 || request.finalTopK > request.candidateTopK || !request.similarityThreshold.isFinite() || request.similarityThreshold !in -1.0..1.0 || request.contextMaxCharacters !in 300..60000 || request.maxOutputTokens != null && request.maxOutputTokens !in 1..32768) throw LabException("invalid_experiment", "Проверьте режимы, вопрос, индекс и параметры. Final top-K не может превышать candidate top-K; режимы не должны повторяться.")
    }
    private fun error(mode: RetrievalMode, failure: Throwable) = ExperimentResult(mode, ExperimentStatus.ERROR, null, safeError(failure), null, null, false)
    private fun safeError(failure: Throwable): ExperimentError {
        if (failure is LabException) return ExperimentError(failure.code, failure.message ?: "Ошибка стадии.")
        log.warn("Experiment stage failure type={}", failure.javaClass.simpleName)
        return ExperimentError("experiment_stage_failed", "Ошибка стадии эксперимента. Автоматический повтор не выполнялся.")
    }
    private fun sumUsage(usages: List<TokenUsage>) = TokenUsage(usages.sumOf { it.promptTokens }, usages.sumOf { it.completionTokens }, usages.sumOf { it.totalTokens }, if (usages.all { it.cacheHitTokens != null }) usages.sumOf { it.cacheHitTokens!! } else null, if (usages.all { it.cacheMissTokens != null }) usages.sumOf { it.cacheMissTokens!! } else null)
}
