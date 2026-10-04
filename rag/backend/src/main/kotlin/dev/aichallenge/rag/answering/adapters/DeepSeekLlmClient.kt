package dev.aichallenge.rag.answering.adapters

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.answering.ports.LlmClient
import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.config.DeepSeekProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.*
import java.time.Duration

/** Один HTTP-вызов без скрытых retry; профиль смысловой проверки отделён от обычной генерации. */
@Component
class DeepSeekLlmClient(private val properties: DeepSeekProperties, private val mapper: ObjectMapper) : LlmClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    override fun settings() = AnswerSettings(properties.apiKey.isNotBlank(), properties.model, 0.0, "disabled", 16000, null, "https://api-docs.deepseek.com/quick_start/pricing/")

    /** Логирует метрики, но не Authorization, промпт, текст ответа или raw error body. */
    override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion {
        return execute(messages, maxOutputTokens, false)
    }
    override fun completeJson(messages: List<LlmMessage>, maxOutputTokens: Int?) = execute(messages, maxOutputTokens, true)
    override fun completeVerifiedJson(messages: List<LlmMessage>, maxOutputTokens: Int?) = execute(
        messages, maxOutputTokens, true,
        model = properties.supportModel, thinkingEnabled = true, reasoningEffort = properties.supportReasoningEffort,
        timeoutSeconds = properties.supportTimeoutSeconds,
    )

    /** Один запрос; JSON object только для явно выбранных структурированных флоу, не старых текстовых ответов. */
    private fun execute(
        messages: List<LlmMessage>, maxOutputTokens: Int?, jsonObject: Boolean,
        model: String = properties.model, thinkingEnabled: Boolean = false, reasoningEffort: String? = null,
        timeoutSeconds: Long = properties.timeoutSeconds,
    ): LlmCompletion {
        if (properties.apiKey.isBlank()) throw LabException("llm_not_configured", "Настройте DEEPSEEK_API_KEY только на backend и пересоздайте контейнер.", HttpStatus.SERVICE_UNAVAILABLE)
        val body = linkedMapOf<String, Any>("model" to model, "messages" to messages, "stream" to false, "temperature" to 0.0, "thinking" to mapOf("type" to if (thinkingEnabled) "enabled" else "disabled"))
        if (thinkingEnabled && reasoningEffort != null) body["reasoning_effort"] = reasoningEffort
        if (maxOutputTokens != null) body["max_tokens"] = maxOutputTokens
        if (jsonObject) body["response_format"] = mapOf("type" to "json_object")
        val request = HttpRequest.newBuilder(URI.create(properties.baseUrl.trimEnd('/') + "/chat/completions"))
            .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json").header("Authorization", "Bearer ${properties.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build()
        val started = System.nanoTime()
        val response = try { http.send(request, HttpResponse.BodyHandlers.ofString()) } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            log.warn("DeepSeek transport failure type={}", error.javaClass.simpleName)
            throw LabException("llm_unavailable", "DeepSeek недоступен или не успел ответить. Автоматический повтор не выполнялся.", HttpStatus.SERVICE_UNAVAILABLE)
        }
        if (response.statusCode() !in 200..299) {
            log.warn("DeepSeek HTTP status={}", response.statusCode())
            val description = when (response.statusCode()) { 401,403 -> "Проверьте серверный API key."; 402 -> "Недостаточно средств у провайдера."; 429 -> "Провайдер ограничил частоту запросов."; else -> "Проверьте настройки модели или повторите позже." }
            throw LabException("llm_provider_error", "DeepSeek HTTP ${response.statusCode()}. $description", HttpStatus.BAD_GATEWAY)
        }
        val json = try { mapper.readTree(response.body()) } catch (_: Exception) { throw invalid("Некорректный JSON провайдера.") }
        val choice = json.path("choices").firstOrNull() ?: throw invalid("Провайдер не вернул choices.")
        val content = choice.path("message").path("content").takeIf { it.isString }?.asText()?.trim().orEmpty()
        if (content.isBlank()) throw LabException("llm_empty_response", "DeepSeek вернул пустой финальный ответ. Попробуйте повторить или увеличить явный лимит ответа.", HttpStatus.BAD_GATEWAY)
        val responseModel = json.path("model").takeIf { it.isString }?.asText()?.takeIf { it.isNotBlank() } ?: throw invalid("Нет имени фактически ответившей модели.")
        val finish = choice.path("finish_reason").takeIf { it.isString }?.asText() ?: throw invalid("Нет finish_reason.")
        if (finish !in setOf("stop", "length")) throw invalid("Неожиданный тип завершения: $finish.")
        val usageNode = json.path("usage")
        val usage = if (usageNode.isMissingNode || usageNode.isNull) null else {
            val prompt = count(usageNode, "prompt_tokens", true)!!
            // API completion_tokens уже включает reasoning: не вычитаем и не прибавляем его отдельно.
            val completion = count(usageNode, "completion_tokens", true)!!
            val total = count(usageNode, "total_tokens", true)!!
            if (total != prompt + completion) throw invalid("Usage API не согласован.")
            val hit = count(usageNode, "prompt_cache_hit_tokens", false)
            val miss = count(usageNode, "prompt_cache_miss_tokens", false)
            if (hit != null && miss != null && hit + miss != prompt) throw invalid("Cache usage API не согласован.")
            TokenUsage(prompt, completion, total, hit, miss)
        }
        val result = LlmCompletion(json.path("id").asText("unknown"), responseModel, content, finish, (System.nanoTime() - started) / 1_000_000, usage)
        log.info("DeepSeek completed model={} latencyMs={} finish={} promptTokens={} outputTokens={}", responseModel, result.milliseconds, finish, usage?.promptTokens, usage?.completionTokens)
        return result
    }
    private fun count(node: JsonNode, name: String, required: Boolean): Long? {
        val value = node.path(name)
        if (!required && (value.isMissingNode || value.isNull)) return null
        if (!value.isIntegralNumber || !value.canConvertToLong() || value.asLong() < 0) throw invalid("Некорректное поле usage: $name.")
        return value.asLong()
    }
    private fun invalid(message: String) = LabException("llm_invalid_response", message, HttpStatus.BAD_GATEWAY)
}
