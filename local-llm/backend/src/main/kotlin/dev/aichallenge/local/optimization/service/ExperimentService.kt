package dev.aichallenge.local.optimization.service

import dev.aichallenge.local.generation.*
import dev.aichallenge.local.optimization.config.OptimizationProperties
import dev.aichallenge.local.optimization.evaluation.*
import dev.aichallenge.local.optimization.models.*
import dev.aichallenge.local.optimization.runtime.*
import dev.aichallenge.local.optimization.skills.*
import dev.aichallenge.local.optimization.storage.ExperimentRepository
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.Executors
import jakarta.annotation.PreDestroy

/** Один эксперимент владеет общим слотом GPU; случаи выполняются последовательно и сохраняются по одному. */
@Service
class ExperimentService(private val config: OptimizationProperties, private val runtime: LocalLlmRuntime,
    private val probe: LocalProbe, private val gateway: ModelGateway, private val catalog: BenchmarkCatalog,
    private val composer: PromptComposer, private val evaluator: CardEvaluator, private val repository: ExperimentRepository,
    private val mapper: ObjectMapper, private val gate: GenerationGate) {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    @PreDestroy fun stop() { executor.shutdownNow() }

    /** Чтение доступности не генерирует ответ и не расходует облачный API. */
    fun settings(): Settings {
        val state = runtime.info()
        val identity = if (state.connected) runCatching { probe.identity(config.localModel) }.getOrNull() else null
        return Settings(config.localModel, state.connected, identity != null, identity, config.cloudEnabled,
            config.cloudEnabled && config.deepseekApiKey.isNotBlank(), config.cloudModels, composer.skill)
    }

    /** UUID с тем же запросом возвращает сохранённый объект даже после смены активной локальной модели. */
    @Synchronized fun start(request: ExperimentRequest): Experiment {
        repository.find(request.id.toString())?.let {
            if (it.request != request) throw OptimizationFailure("Этот UUID уже используется другим запросом.")
            return it
        }
        if ((request.caseIds.isEmpty() && request.input.isBlank()) || (request.caseIds.isNotEmpty() && request.input.isNotBlank()))
            throw OptimizationFailure("Выберите тесты либо введите своё обращение, но не оба варианта одновременно.")
        if (request.caseIds.distinct().size != request.caseIds.size) throw OptimizationFailure("Тестовые случаи не должны повторяться в списке.")
        val selected = request.caseIds.map(catalog::find)
        val model = if (request.provider == Provider.LOCAL) config.localModel else request.cloudModel
        if (request.provider == Provider.DEEPSEEK && (!config.cloudEnabled || config.deepseekApiKey.isBlank() || model !in config.cloudModels))
            throw OptimizationFailure("DeepSeek отключён, ключ не настроен или модель не разрешена.")
        val lease = gate.reserve()
        try {
            val state = if (request.provider == Provider.LOCAL) runtime.info() else null
            if (state != null && (!state.connected || state.models.none { it.name == model })) throw OptimizationFailure("Выбранная локальная модель не установлена или Ollama недоступна.")
            val identity = if (request.provider == Provider.LOCAL) probe.identity(model) ?: throw OptimizationFailure("Нет метаданных выбранной локальной модели.") else null
            val inputs = if (selected.isEmpty()) listOf(Triple(null, "Своё обращение", request.input.trim()))
                else selected.map { Triple(it.id, it.title, it.input) }
            val items = (1..request.repetitions).flatMap { repetition -> inputs.map { (caseId, title, input) ->
                val messages = composer.compose(input, request.promptProfile, request.skillEnabled)
                ExperimentItem("${caseId ?: "custom"}-$repetition", caseId, title, repetition, input,
                    selected.firstOrNull { it.id == caseId }?.expected, messages, hash(mapper.writeValueAsString(messages)))
            } }
            val initial = Experiment(request.id.toString(), Instant.now().toString(), request, model, identity, state?.version,
                composer.version, if (request.skillEnabled) composer.skill else null, ExperimentStatus.RUNNING, items)
            repository.save(initial)
            executor.submit { lease.use { execute(initial) } }
            return initial
        } catch (e: Exception) { lease.close(); throw e }
    }

    /** Не повторяет неудачный case и не исправляет его JSON. Ошибки остаются частью сравнения. */
    private fun execute(initial: Experiment) {
        var current = initial
        try {
            for (index in current.items.indices) {
                val item = current.items[index].copy(status = ItemStatus.RUNNING)
                current = current.copy(items = current.items.toMutableList().also { it[index] = item }); repository.save(current)
                val monitor = if (current.request.provider == Provider.LOCAL) probe.monitor(current.model, current.request.parameters.contextSize) else null
                val start = System.nanoTime()
                val result = try {
                    val answer = gateway.generate(current.request.provider, current.model, item.messages, current.request.parameters)
                    item.copy(status = if (answer.doneReason == "length") ItemStatus.TRUNCATED else ItemStatus.COMPLETED,
                        generation = answer, evaluation = evaluator.evaluate(answer.answer, item.expected))
                } catch (e: Exception) {
                    val message = if (e is RuntimeFailure || e is InvalidModel || e is OptimizationFailure) e.message else "Вызов завершился ошибкой; автоматического повтора нет."
                    item.copy(status = ItemStatus.FAILED, error = message)
                } finally { monitor?.close() }
                val finished = result.copy(elapsedMilliseconds = (System.nanoTime() - start) / 1_000_000, resources = monitor?.result())
                current = current.copy(items = current.items.toMutableList().also { it[index] = finished }); repository.save(current)
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
            }
            val complete = current.items.count { it.status == ItemStatus.COMPLETED }
            current = current.copy(status = when { complete == current.items.size -> ExperimentStatus.COMPLETED;
                current.items.any { it.generation != null } -> ExperimentStatus.PARTIAL; else -> ExperimentStatus.FAILED })
            repository.save(current)
        } catch (_: Exception) {
            repository.save(current.copy(status = ExperimentStatus.INTERRUPTED, items = current.items.map {
                if (it.status in setOf(ItemStatus.PENDING, ItemStatus.RUNNING)) it.copy(status = ItemStatus.INTERRUPTED, error = "Эксперимент прерван; повтор не запущен.") else it
            }))
        }
    }
}
