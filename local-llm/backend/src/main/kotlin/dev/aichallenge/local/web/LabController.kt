package dev.aichallenge.local.web

import dev.aichallenge.local.generation.*
import dev.aichallenge.local.runs.RunRepository
import dev.aichallenge.local.runs.toMarkdown
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/** HTTP-контракт лаборатории: runtime, сценарии и запуск локальной генерации. */
@RestController
@RequestMapping("/api/v1")
class LabController(private val runtime: LocalLlmRuntime, private val service: GenerationService, private val repository: RunRepository) {
    @GetMapping("/runtime") fun runtime(): RuntimeInfo = runtime.info()
    @GetMapping("/scenarios") fun scenarios(): List<Scenario> = day26Scenarios
    @GetMapping("/runs") fun runs(): List<RunSummary> = repository.summaries()
    @GetMapping("/runs/{id}") fun run(@PathVariable id: String): Run = repository.find(id)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Запуск не найден.")
    /** Отдаёт сохранённый результат обычным HTTP-файлом для скачивания из любого браузера. */
    @GetMapping("/runs/{id}/report") fun report(@PathVariable id: String): ResponseEntity<String> {
        val saved = run(id)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"day26-${saved.id}.md\"")
            .body(saved.toMarkdown())
    }
    @PostMapping("/runs") fun generate(@Valid @RequestBody request: GenerateRequest): Run = service.generate(request)
}

/** Ожидания помогают проверить содержательный ответ, а не только HTTP 200. */
data class Scenario(val id: String, val title: String, val difficulty: String, val prompt: String, val expectation: String)

val day26Scenarios = listOf(
    Scenario("simple", "Короткий ответ", "Простой",
        "Назови столицу Франции. Ответь одним предложением на русском языке.", "В ответе назван Париж."),
    Scenario("reasoning", "Задача с условиями", "Средний",
        "Магазин даёт скидку 20% на товар стоимостью 1500 рублей. Если сумма после скидки меньше 1300 рублей, доставка стоит 200 рублей, иначе бесплатна. Сколько всего заплатит покупатель? Покажи короткий расчёт на русском языке.",
        "После скидки 1200 рублей; доставка 200 рублей; итого 1400 рублей."),
    Scenario("code", "Алгоритм и проверка", "Сложный",
        "Напиши функцию two_sum(nums, target) на Python за O(n), которая возвращает индексы двух различных элементов с заданной суммой. Нельзя использовать один элемент дважды. Если пары нет, верни None. Покажи результаты для [2,7,11,15], 9; [3,3], 6; [1,2,3], 7. Кратко объясни сложность на русском языке.",
        "Hash map, проверка дополнения до записи текущего элемента, O(n) времени и O(n) памяти; результаты [0,1], [0,1], None."),
)
