package dev.aichallenge.local.rag.experiments

import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.generation.*
import dev.aichallenge.local.rag.config.RagProperties
import dev.aichallenge.local.rag.generation.RagGenerator
import dev.aichallenge.local.rag.models.*
import dev.aichallenge.local.rag.retrieval.RetrievalService
import dev.aichallenge.local.rag.storage.RagRepository
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Асинхронный независимый вопрос: один retrieval, затем параллельные провайдеры с отдельными исходами. */
@Service
class ExperimentService(private val repository: RagRepository, private val retrieval: RetrievalService,
    generators: List<RagGenerator>, private val config: RagProperties, private val local: LabProperties, private val gate: GenerationGate) {
    private val providers = generators.associateBy { it.provider }
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val active = AtomicBoolean(false)
    private val log = LoggerFactory.getLogger(javaClass)

    /** UUID — идемпотентный ID: потеря HTTP-ответа не должна повторять платную генерацию. */
    @Synchronized
    fun start(request: RagRequest): RagExperiment {
        try { UUID.fromString(request.requestId) } catch (_: Exception) { throw RagFailure("Нужен UUID requestId.") }
        val clean = request.copy(question = request.question.trim())
        repository.findExperiment(clean.requestId)?.let {
            if (it.request != clean) throw RagFailure("Этот requestId уже использован с другими параметрами.")
            return it
        }
        if (clean.question.isBlank() || clean.question.length > 2000 || clean.topK !in 1..12 || !clean.threshold.isFinite() || clean.threshold !in 0.0..1.0 || clean.contextCharacters !in 1500..12000 || !clean.temperature.isFinite() || clean.temperature !in 0.0..2.0 || clean.maxOutputTokens != null && clean.maxOutputTokens !in 1..8192) throw RagFailure("Некорректные параметры RAG.")
        if (clean.mode != RagMode.DEEPSEEK && clean.localModel !in local.models) throw RagFailure("Локальная модель не разрешена.")
        if (clean.mode != RagMode.LOCAL && (!config.cloudEnabled || config.deepseekApiKey.isBlank() || clean.cloudModel !in config.deepseekModels)) throw RagFailure("DeepSeek отключён, нет ключа или модель не разрешена.")
        if (repository.index(clean.indexId).status != IndexStatus.READY) throw RagFailure("Выберите готовый индекс.")
        if (!active.compareAndSet(false, true)) throw RagFailure("Другой RAG-запрос ещё выполняется. Скрытой очереди нет.")
        val initial = RagExperiment(clean.requestId, Instant.now().toString(), clean)
        try { repository.saveExperiment(initial) } catch (failure: Exception) { active.set(false); throw failure }
        workers.submit { execute(initial) }
        return initial
    }
    private fun execute(initial: RagExperiment) {
        val started = System.nanoTime()
        try { gate.exclusive {
            val prepared = retrieval.prepare(initial.request)
            if (prepared.snapshot.included.isEmpty()) {
                repository.updateExperiment(initial.id) { it.copy(status = ExperimentStatus.UNKNOWN, retrieval = prepared.snapshot,
                    milliseconds = elapsed(started), error = "Не знаю по найденным фрагментам. Уточните вопрос, область поиска или порог; модели не вызывались.") }
                return@exclusive
            }
            val names = when (initial.request.mode) { RagMode.LOCAL -> listOf("LOCAL"); RagMode.DEEPSEEK -> listOf("DEEPSEEK"); RagMode.COMPARE -> listOf("LOCAL", "DEEPSEEK") }
            repository.updateExperiment(initial.id) { it.copy(status = ExperimentStatus.RUNNING, retrieval = prepared.snapshot,
                answers = names.map { name -> ModelAnswer(name, if (name == "LOCAL") initial.request.localModel else initial.request.cloudModel) }) }
            val tasks = names.map { name -> workers.submit {
                val modelStart = System.nanoTime(); val began = Instant.now().toString()
                repository.updateExperiment(initial.id) { run -> run.copy(answers = run.answers.map { if (it.provider == name) it.copy(startedAt = began) else it }) }
                val result = try {
                    val generated = providers.getValue(name).generate(initial.request, prepared.messages)
                    val refs = Regex("\\[(S\\d+)\\]").findAll(generated.answer.orEmpty()).map { it.groupValues[1] }.distinct().toList()
                    val allowed = prepared.snapshot.included.map { it.ref }.toSet()
                    generated.copy(citedRefs = refs, citationStatus = when {
                        refs.any { it !in allowed } -> CitationStatus.INVALID
                        refs.isEmpty() -> CitationStatus.MISSING
                        else -> CitationStatus.VALID
                    })
                } catch (failure: Exception) { ModelAnswer(name, if (name == "LOCAL") initial.request.localModel else initial.request.cloudModel,
                    status = RunStatus.FAILED, error = if (failure is RuntimeFailure || failure is InvalidModel || failure is RagFailure) failure.message?.take(300) else "Ошибка стадии генерации; повтор не выполнялся.") }
                val final = result.copy(startedAt = began, finishedAt = Instant.now().toString(), milliseconds = elapsed(modelStart))
                repository.updateExperiment(initial.id) { run -> run.copy(answers = run.answers.map { if (it.provider == name) final else it }) }
                log.info("RAG model completed id={} provider={} status={} elapsedMs={}", initial.id, name, final.status, final.milliseconds)
            } }
            tasks.forEach { it.get() }
            repository.updateExperiment(initial.id) { run ->
                val completed = run.answers.count { it.status == RunStatus.COMPLETED }
                run.copy(status = when { completed == run.answers.size -> ExperimentStatus.COMPLETED; completed > 0 -> ExperimentStatus.PARTIAL; else -> ExperimentStatus.FAILED }, milliseconds = elapsed(started))
            }
        } } catch (failure: Exception) {
            repository.updateExperiment(initial.id) { it.copy(status = ExperimentStatus.FAILED, milliseconds = elapsed(started),
                error = if (failure is RagFailure || failure is LabBusy) failure.message else "Ошибка подготовки или сохранения. Проверьте состояние сервиса.",
                answers = it.answers.map { answer -> if (answer.status == RunStatus.RUNNING) answer.copy(status = RunStatus.FAILED, finishedAt = Instant.now().toString(), error = "Стадия прервана; результат не подтверждён.") else answer }) }
        } finally { active.set(false) }
    }
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000
    @PreDestroy fun close() { workers.shutdownNow() }
}
