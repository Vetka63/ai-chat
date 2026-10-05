package dev.aichallenge.local.generation

import jakarta.validation.constraints.*

/** Параметры одной независимой генерации. История прошлых запусков не подмешивается в prompt. */
data class GenerateRequest(
    @field:NotBlank val model: String,
    @field:NotBlank @field:Size(max = 16000) val prompt: String,
    @field:DecimalMin("0.0") @field:DecimalMax("2.0") val temperature: Double = 0.7,
    val thinking: Boolean = false,
    @field:Min(1) @field:Max(32768) val maxOutputTokens: Int? = null,
    val scenarioId: String? = null,
)

/** Состояние запуска: ошибка и исчерпание лимита не маскируются под успешный результат. */
enum class RunStatus { RUNNING, COMPLETED, TRUNCATED, FAILED }

/** Итог Ollama; durations переведены из наносекунд в миллисекунды. */
data class Generation(
    val answer: String,
    val thinking: String?,
    val doneReason: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalMilliseconds: Double?,
    val loadMilliseconds: Double?,
    val tokensPerSecond: Double?,
)

/** Сохранённый сервером запуск, доступный в любом браузере. */
data class Run(
    val id: String,
    val createdAt: String,
    val request: GenerateRequest,
    val status: RunStatus,
    val generation: Generation? = null,
    val elapsedMilliseconds: Long? = null,
    val error: String? = null,
)

/** Краткая строка списка; полный ответ открывается по ID. */
data class RunSummary(val id: String, val createdAt: String, val prompt: String, val model: String, val status: RunStatus)

/** Установленная локальная модель, разрешённая конфигурацией лаборатории. */
data class LocalModel(val name: String, val sizeBytes: Long, val parameterSize: String?, val quantization: String?)
data class RuntimeInfo(val connected: Boolean, val version: String?, val configuredModels: List<String>, val models: List<LocalModel>, val error: String? = null)

/** Контракт runtime: следующие дни смогут добавлять адаптеры без изменения сценариев и UI API. */
interface LocalLlmRuntime {
    fun info(): RuntimeInfo
    fun generate(request: GenerateRequest): Generation
}

class RuntimeFailure(message: String) : RuntimeException(message)
class InvalidModel(message: String) : RuntimeException(message)
