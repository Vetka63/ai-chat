package dev.aichallenge.local.optimization.runtime

import dev.aichallenge.local.generation.*
import dev.aichallenge.local.optimization.config.OptimizationProperties
import dev.aichallenge.local.optimization.models.*
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.*
import java.time.Duration

/** Порт генерации: общие сообщения и оценка не зависят от провайдера. */
interface ModelGateway {
    fun generate(provider: Provider, model: String, messages: List<LlmMessage>, parameters: Parameters): Generation
}

/** Маршрутизирует явный выбор; не подменяет Ollama облаком и не повторяет платные вызовы. */
@Component
class ProviderGateway(private val local: LocalLlmRuntime, private val cloud: DeepSeekGateway) : ModelGateway {
    override fun generate(provider: Provider, model: String, messages: List<LlmMessage>, parameters: Parameters): Generation =
        if (provider == Provider.LOCAL) local.chat(LlmChatRequest(model, messages, parameters.temperature, parameters.thinking,
            parameters.maxTokens, parameters.contextSize, parameters.topP, parameters.topK, parameters.seed))
        else cloud.generate(model, messages, parameters)
}

/** Единственный облачный HTTP-адаптер. Ключ идёт только в Authorization, raw ошибки провайдера не публикуются. */
@Component
class DeepSeekGateway(private val config: OptimizationProperties, private val mapper: ObjectMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    fun generate(model: String, messages: List<LlmMessage>, parameters: Parameters): Generation {
        if (!config.cloudEnabled || config.deepseekApiKey.isBlank()) throw OptimizationFailure("DeepSeek отключён или ключ не настроен на backend.")
        if (model !in config.cloudModels) throw OptimizationFailure("Модель DeepSeek не разрешена конфигурацией.")
        val payload = mutableMapOf<String, Any>("model" to model,
            "messages" to messages.map { mapOf("role" to it.role.name.lowercase(), "content" to it.content) },
            "stream" to false, "max_tokens" to parameters.maxTokens,
            "thinking" to mapOf("type" to if (parameters.thinking) "enabled" else "disabled"))
        // DeepSeek v4: temperature не влияет на thinking; top_p не влияет на non-thinking.
        if (parameters.thinking) { payload["reasoning_effort"] = "high"; payload["top_p"] = maxOf(0.95, parameters.topP) }
        else payload["temperature"] = parameters.temperature
        try {
            val request = HttpRequest.newBuilder(URI.create(config.deepseekBaseUrl.trimEnd('/') + "/chat/completions"))
                .timeout(Duration.ofSeconds(config.timeoutSeconds)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${config.deepseekApiKey}")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) throw OptimizationFailure("DeepSeek HTTP ${response.statusCode()}; вызов не повторён. Проверьте ключ, баланс и доступность модели.")
            val node = mapper.readTree(response.body())
            val choice = node.path("choices").get(0) ?: throw OptimizationFailure("DeepSeek вернул ответ без choices.")
            val reason = choice.path("finish_reason").asText()
            if (reason !in setOf("stop", "length")) throw OptimizationFailure("DeepSeek вернул неподдерживаемое завершение.")
            val content = choice.path("message").path("content")
            if ((!content.isTextual && !(content.isNull && reason == "length")) || (content.isTextual && content.asText().isBlank() && reason != "length")) throw OptimizationFailure("DeepSeek не вернул текст ответа.")
            val thinking = choice.path("message").path("reasoning_content")
            val usage = node.path("usage")
            fun tokens(key: String): Int? = if (usage.path(key).isNumber) usage.path(key).asInt() else null
            return Generation(if (content.isTextual) content.asText() else "", if (thinking.isTextual) thinking.asText() else null, reason,
                tokens("prompt_tokens"), tokens("completion_tokens"), null, null, null,
                if (node.path("model").isTextual) node.path("model").asText() else null)
        } catch (e: OptimizationFailure) { throw e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw OptimizationFailure("Облачный запрос прерван; автоматического повтора нет.") }
        catch (_: Exception) { throw OptimizationFailure("Не удалось получить корректный ответ DeepSeek; автоматического повтора нет.") }
    }
}

/** Фиксирует реально применимые параметры, чтобы UI не обещал настройку окна контекста облака. */
fun effectiveParameters(provider: Provider, p: Parameters): Map<String, Any?> = if (provider == Provider.LOCAL)
    mapOf("temperature" to p.temperature, "max_tokens" to p.maxTokens, "context_window" to p.contextSize,
        "thinking" to p.thinking, "top_p" to p.topP, "top_k" to p.topK, "seed" to p.seed)
else mapOf("temperature" to if (p.thinking) null else p.temperature, "max_tokens" to p.maxTokens,
    "context_window" to null, "thinking" to p.thinking, "top_p" to if (p.thinking) maxOf(0.95, p.topP) else 1.0,
    "top_k" to null, "seed" to null)
