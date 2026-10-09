package dev.aichallenge.local.rag.web

import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.rag.config.RagProperties
import dev.aichallenge.local.rag.corpus.CorpusService
import dev.aichallenge.local.rag.experiments.ExperimentService
import dev.aichallenge.local.rag.indexing.IndexService
import dev.aichallenge.local.rag.models.*
import dev.aichallenge.local.rag.storage.RagRepository
import jakarta.validation.Valid
import org.springframework.http.*
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

/** HTTP-фасад дня 28; принимает тексты, но не пути к файлам сервера, URL или API-ключи. */
@RestController
@RequestMapping("/api/v1/rag")
class RagController(private val corpus: CorpusService, private val indexes: IndexService, private val experiments: ExperimentService,
    private val repository: RagRepository, private val config: RagProperties, private val local: LabProperties,
    private val retrieval: dev.aichallenge.local.rag.retrieval.RetrievalService, private val gate: dev.aichallenge.local.generation.GenerationGate) {
    /** Диагностический поиск без вызова генераторов, в том числе без DeepSeek. */
    @PostMapping("/search") fun search(@Valid @RequestBody request: RagRequest) = gate.exclusive { retrieval.prepare(request).snapshot }
    @GetMapping("/settings") fun settings() = RagSettings(config.embeddingModel, config.cloudEnabled,
        config.cloudEnabled && config.deepseekApiKey.isNotBlank(), config.deepseekModels, local.contextSize)
    @GetMapping("/documents") fun documents() = corpus.documents()
    @PostMapping("/documents", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@RequestPart file: MultipartFile, @RequestParam title: String, @RequestParam(required = false) startMarker: String?) =
        corpus.import(file.bytes, file.originalFilename ?: "document.txt", title, startMarker)
    @GetMapping("/indexes") fun indexes() = repository.indexes()
    @PostMapping("/indexes") @ResponseStatus(HttpStatus.ACCEPTED)
    fun index(@Valid @RequestBody request: IndexRequest) = indexes.start(request)
    @GetMapping("/indexes/{id}") fun index(@PathVariable id: String) = repository.index(id)
    @GetMapping("/indexes/{id}/chunks") fun chunks(@PathVariable id: String) = repository.vectors(id).map { it.chunk }
    @GetMapping("/experiments") fun experiments() = repository.summaries()
    @PostMapping("/experiments") @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(@Valid @RequestBody request: RagRequest) = experiments.start(request)
    @GetMapping("/experiments/{id}") fun experiment(@PathVariable id: String) = repository.experiment(id)
    /** Ручной вывод о качестве/скорости/стабильности; не является автоматическим LLM judge. */
    @PatchMapping("/experiments/{id}/conclusion")
    fun conclusion(@PathVariable id: String, @Valid @RequestBody request: ConclusionRequest) = repository.updateExperiment(id) {
        if (it.status in setOf(ExperimentStatus.PREPARING, ExperimentStatus.RUNNING)) throw RagFailure("Дождитесь завершения эксперимента.")
        it.copy(conclusion = request.text.trim().takeIf { text -> text.isNotBlank() })
    }
    @GetMapping("/experiments/{id}/report") fun report(@PathVariable id: String): ResponseEntity<String> = ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=day28-$id.md")
        .body(reportText(repository.experiment(id)))
}

/** Экспорт не вызывает модель; включает исходные параметры, фактические ответы и пределы проверки. */
fun reportText(run: RagExperiment): String = buildString {
    appendLine("# День 28 · Локальный RAG")
    appendLine("\nID: ${run.id}\nДата: ${run.createdAt}\nСтатус: ${run.status}\nРежим: ${run.request.mode}\nИндекс: ${run.request.indexId}")
    appendLine("\n## Вопрос\n"); literal(run.request.question)
    appendLine("\n## Общий контекст\n\nRetrieval: ${run.retrieval?.milliseconds ?: "неизвестно"} мс; общее время: ${run.milliseconds ?: "неизвестно"} мс.")
    appendLine("\nPrompt SHA-256: ${run.retrieval?.promptSha256 ?: "не сформирован"}. Top-K: ${run.request.topK}; threshold: ${run.request.threshold}; temperature: ${run.request.temperature}; thinking: ${run.request.thinking}; output cap: ${run.request.maxOutputTokens ?: "не задан"}.")
    appendLine("\nEmbedding вопроса из локального кеша: ${run.retrieval?.embeddingCacheHit ?: false}; embedding API tokens этого поиска: ${run.retrieval?.embeddingTokens ?: "неизвестно"}. Ответы не кешируются.")
    run.error?.let { appendLine("\nОшибка/уточнение: $it") }
    run.answers.forEach { answer ->
        appendLine("\n## ${answer.provider} · ${answer.actualModel ?: answer.requestedModel}\n\nСтатус: ${answer.status}; время: ${answer.milliseconds ?: "неизвестно"} мс.")
        appendLine("\nНачало: ${answer.startedAt}; конец: ${answer.finishedAt}. Input/output: ${answer.inputTokens ?: "неизвестно"}/${answer.outputTokens ?: "неизвестно"}; загрузка: ${answer.loadMilliseconds ?: "неизвестно"} мс; decoding: ${answer.tokensPerSecond ?: "неизвестно"} токенов/с.")
        appendLine(); literal(answer.answer ?: answer.error ?: "Ожидание")
        answer.thinking?.takeIf { it.isNotBlank() }?.let { appendLine("\n### Thinking модели\n"); literal(it) }
        appendLine("\nПроверка ID ссылок: ${answer.citationStatus}; использованы: ${answer.citedRefs.joinToString()}.")
    }
    appendLine("\n## Вывод пользователя\n\n${run.conclusion ?: "Не сохранён. Сравните качество, скорость и повторные запуски."}")
    appendLine("\n## Переданные источники")
    run.retrieval?.included.orEmpty().forEach { hit ->
        appendLine("\n### [${hit.ref}] ${hit.chunk.title} · ${hit.chunk.section}\n\nSource: ${hit.chunk.source}; chunk_id: ${hit.chunk.id}; similarity: ${hit.similarity}.\n")
        literal(hit.chunk.text)
    }
    appendLine("\n## Ограничения\n\nПроверка ID подтверждает существование ссылки, но не смысловую поддержку утверждений. Качество оценивается по контрольным вопросам. В LOCAL нет облачных rewrite/judge/fallback. В COMPARE найденные фрагменты передаются DeepSeek. Токенизаторы различаются; token counts не являются прямой мерой качества. Локальные вычисления не имеют API-платы, но расходуют ресурсы компьютера.")
}

/** Экспорт сохраняет Markdown/HTML из вопроса, источника или ответа как буквальные данные. */
private fun StringBuilder.literal(text: String) {
    val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1))
    appendLine("${fence}text"); appendLine(text); appendLine(fence); appendLine()
}
