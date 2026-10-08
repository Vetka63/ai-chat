package dev.aichallenge.local.optimization.web

import dev.aichallenge.local.optimization.evaluation.BenchmarkCatalog
import dev.aichallenge.local.optimization.models.*
import dev.aichallenge.local.optimization.runtime.effectiveParameters
import dev.aichallenge.local.optimization.service.ExperimentService
import dev.aichallenge.local.optimization.storage.ExperimentRepository
import jakarta.validation.Valid
import org.springframework.http.*
import org.springframework.web.bind.annotation.*

/** HTTP API лаборатории: чтение и экспорт никогда не вызывают модель повторно. */
@RestController
@RequestMapping("/api/v1/optimization")
class OptimizationController(private val service: ExperimentService, private val catalog: BenchmarkCatalog, private val repository: ExperimentRepository) {
    @GetMapping("/settings") fun settings() = service.settings()
    @GetMapping("/cases") fun cases() = catalog.cases
    @GetMapping("/experiments") fun list() = repository.summaries()
    @PostMapping("/experiments") fun start(@Valid @RequestBody request: ExperimentRequest) = ResponseEntity.accepted().body(service.start(request))
    @GetMapping("/experiments/{id}") fun find(@PathVariable id: String) = repository.find(id) ?: throw OptimizationMissing()
    @GetMapping("/experiments/{id}/parameters") fun parameters(@PathVariable id: String) = find(id).let { effectiveParameters(it.request.provider, it.request.parameters) }
    @PatchMapping("/experiments/{id}/note") fun note(@PathVariable id: String, @RequestBody body: NoteRequest) = repository.note(id, body.note)
    @GetMapping("/experiments/{id}/report", produces = ["text/markdown;charset=UTF-8"])
    fun report(@PathVariable id: String): ResponseEntity<String> {
        val e = find(id)
        val text = buildString {
            appendLine("# День 29 — оптимизация локальной LLM\n")
            appendLine("Эксперимент: ${e.id}; ${e.createdAt}; статус: ${e.status}.")
            appendLine("Провайдер: ${e.request.provider}; модель: ${e.model}; actual model: ${e.items.firstNotNullOfOrNull { it.generation?.actualModel }}.")
            appendLine("Квантование: ${e.identity?.quantization ?: "не предоставлено"}; digest: ${e.identity?.digest ?: "не предоставлен"}; Ollama: ${e.runtimeVersion ?: "—"}.")
            appendLine("Промпт: ${e.request.promptProfile} / ${e.promptVersion}; skill: ${e.skill?.id ?: "выключен"}, hash: ${e.skill?.sha256 ?: "—"}.")
            appendLine("Запрошенные параметры: ${e.request.parameters}.")
            appendLine("Применимые параметры: ${effectiveParameters(e.request.provider, e.request.parameters)}.\n")
            appendLine("COMPLETED означает завершённый вызов, не правильный ответ. Оценка пяти полей точная; резюме проверяется человеком.")
            appendLine("Память: sampled model allocation Ollama /api/ps, не RSS и не полное потребление GPU. Облачные ресурсы неизвестны.\n")
            e.items.forEach { item ->
                appendLine("## ${item.title} · повтор ${item.repetition}\n")
                appendLine("Статус: ${item.status}; prompt SHA256: ${item.promptSha256}.")
                appendLine("Время HTTP + метаданные + telemetry: ${item.elapsedMilliseconds ?: "—"} мс; runtime: ${item.generation?.totalMilliseconds ?: "—"} мс; загрузка: ${item.generation?.loadMilliseconds ?: "—"} мс.")
                appendLine("Токены вход/выход: ${item.generation?.inputTokens ?: "—"}/${item.generation?.outputTokens ?: "—"}; токены/с: ${item.generation?.tokensPerSecond ?: "—"}.")
                appendLine("Ресурсы: ${item.resources ?: "недоступны"}.")
                appendLine("JSON/схема: ${item.evaluation?.validJson}/${item.evaluation?.validSchema}; поля: ${item.evaluation?.matchedFields ?: "—"}/${item.evaluation?.totalFields ?: "—"}.")
                item.evaluation?.mismatches?.forEach { appendLine("- $it") }
                appendLine("\n### Обращение\n"); appendLine(item.input)
                appendLine("\n### Ответ\n"); appendLine(item.generation?.answer ?: item.error ?: "Ответ ещё не получен.")
                appendLine("\n### Точные сообщения\n"); item.messages.forEach { appendLine("${it.role}: ${it.content}\n") }
            }
            appendLine("## Вывод пользователя\n"); appendLine(e.note.ifBlank { "Пока не заполнен." })
            appendLine("\nСравнение: одинаковый набор входов, prompt и параметры при проверке квантования; холодную загрузку отделять от прогретых вызовов. Seed не гарантирует идентичность на разных runtime/железе.")
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=day29-${e.id}.md").body(text)
    }
}
data class NoteRequest(val note: String)
