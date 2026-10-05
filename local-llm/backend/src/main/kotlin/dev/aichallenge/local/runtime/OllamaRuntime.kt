package dev.aichallenge.local.runtime

import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.generation.*
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/** Единственный адаптер HTTP к локальной Ollama. Автоматических повторов генерации нет. */
@Component
class OllamaRuntime(private val config: LabProperties, private val mapper: ObjectMapper) : LocalLlmRuntime {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    /** Возвращает реально установленные модели и состояние связи с runtime. */
    override fun info(): RuntimeInfo = try {
        val tags = call("/api/tags", timeout = 10)
        val models = tags.path("models").filter { node ->
            node.path("name").asText() in config.models && !node.hasNonNull("remote_host") && !node.hasNonNull("remote_model")
        }.map { node ->
            LocalModel(node.path("name").asText(), node.path("size").asLong(),
                node.path("details").optionalText("parameter_size"), node.path("details").optionalText("quantization_level"))
        }
        RuntimeInfo(true, call("/api/version", timeout = 10).path("version").asText(), config.models, models)
    } catch (e: RuntimeFailure) { RuntimeInfo(false, null, config.models, emptyList(), e.message) }

    /** Проверяет наличие локальной completion-модели и отправляет один запрос без cloud fallback. */
    override fun generate(request: GenerateRequest): Generation {
        if (request.model !in config.models) throw InvalidModel("Модель не разрешена серверной конфигурацией.")
        val available = info()
        if (!available.connected) throw RuntimeFailure(available.error ?: "Ollama недоступна.")
        if (available.models.none { it.name == request.model }) throw InvalidModel("Модель не установлена локально. Выполните ollama pull ${request.model}.")
        val show = call("/api/show", mapOf("model" to request.model), 10)
        if (show.path("capabilities").none { it.asText() == "completion" }) throw InvalidModel("Это не модель генерации текста.")
        val options = mutableMapOf<String, Any>("temperature" to request.temperature, "num_ctx" to config.contextSize)
        request.maxOutputTokens?.let { options["num_predict"] = it }
        val payload = mapOf("model" to request.model, "messages" to listOf(mapOf("role" to "user", "content" to request.prompt)),
            "stream" to false, "think" to request.thinking, "options" to options, "keep_alive" to "5m")
        val response = call("/api/chat", payload, config.timeoutSeconds)
        if (!response.path("done").asBoolean()) throw RuntimeFailure("Ollama вернула незавершённый ответ.")
        val reason = response.path("done_reason").asText("unknown")
        if (reason !in setOf("stop", "length")) throw RuntimeFailure("Ollama вернула неизвестную причину завершения: $reason.")
        val answer = response.path("message").path("content").asText()
        if (answer.isBlank()) throw RuntimeFailure("Ollama не вернула текст ответа. Возможно, лимит ушёл на reasoning; увеличьте его или отключите reasoning.")
        val evalNs = if (response.hasNonNull("eval_duration")) response.path("eval_duration").asLong() else null
        val count = if (response.hasNonNull("eval_count")) response.path("eval_count").asInt() else null
        fun milliseconds(name: String): Double? = if (response.hasNonNull(name) && response.path(name).isNumber) response.path(name).asDouble() / 1_000_000 else null
        fun tokens(name: String): Int? = if (response.hasNonNull(name) && response.path(name).isNumber) response.path(name).asInt() else null
        return Generation(answer, response.path("message").optionalText("thinking"),
            reason, tokens("prompt_eval_count"), tokens("eval_count"),
            milliseconds("total_duration"), milliseconds("load_duration"),
            if (count != null && evalNs != null && evalNs > 0) count * 1_000_000_000.0 / evalNs else null)
    }

    /** Отсутствующие optional-поля не превращаются в ошибку приведения MissingNode в Jackson 3. */
    private fun JsonNode.optionalText(name: String): String? =
        if (hasNonNull(name) && path(name).isTextual) path(name).asText() else null

    /** Разбирает протокольные ошибки; вывод не содержит environment или ключей. */
    private fun call(path: String, body: Any? = null, timeout: Long): JsonNode {
        try {
            val builder = HttpRequest.newBuilder(URI.create(config.baseUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(timeout))
            if (body == null) builder.GET() else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) throw RuntimeFailure("Ollama HTTP ${response.statusCode()}: " + response.body().take(300))
            val json = mapper.readTree(response.body())
            if (json.hasNonNull("error")) throw RuntimeFailure("Ollama: " + json.path("error").asText().take(300))
            return json
        } catch (e: RuntimeFailure) { throw e }
        catch (e: HttpTimeoutException) { throw RuntimeFailure("Истекло время ожидания Ollama. Запрос автоматически не повторяется.") }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw RuntimeFailure("Запрос Ollama был прерван.") }
        catch (e: Exception) { throw RuntimeFailure("Не удалось получить корректный ответ от Ollama (${e.javaClass.simpleName}).") }
    }
}
