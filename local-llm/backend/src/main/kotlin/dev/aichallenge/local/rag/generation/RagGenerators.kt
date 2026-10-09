package dev.aichallenge.local.rag.generation

import dev.aichallenge.local.generation.*
import dev.aichallenge.local.rag.config.RagProperties
import dev.aichallenge.local.rag.models.*
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.*
import java.time.Duration

/** Генератор не выполняет retrieval, не меняет контекст и не выбирает другой провайдер при ошибке. */
interface RagGenerator { val provider: String; fun generate(request: RagRequest, messages: List<LlmMessage>): ModelAnswer }

/** Повторно использует локальный runtime дней 26–27, но не историю чатов. */
@Component
class LocalRagGenerator(private val runtime: LocalLlmRuntime) : RagGenerator {
    override val provider = "LOCAL"
    override fun generate(request: RagRequest, messages: List<LlmMessage>): ModelAnswer {
        val response = runtime.chat(LlmChatRequest(request.localModel, messages, request.temperature, request.thinking, request.maxOutputTokens))
        return ModelAnswer(provider, request.localModel, actualModel = response.actualModel ?: request.localModel,
            status = if (response.doneReason == "length") RunStatus.TRUNCATED else RunStatus.COMPLETED,
            answer = response.answer, inputTokens = response.inputTokens?.toLong(), outputTokens = response.outputTokens?.toLong(),
            loadMilliseconds = response.loadMilliseconds, tokensPerSecond = response.tokensPerSecond, finishReason = response.doneReason,
            thinking = response.thinking)
    }
}

/** Опциональный HTTP-клиент DeepSeek: один запрос, ключ только в Authorization, без raw body ошибок. */
@Component
class DeepSeekRagGenerator(private val config: RagProperties, private val mapper: ObjectMapper) : RagGenerator {
    override val provider = "DEEPSEEK"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    override fun generate(request: RagRequest, messages: List<LlmMessage>): ModelAnswer {
        if (!config.cloudEnabled || config.deepseekApiKey.isBlank()) throw RagFailure("Облачное сравнение отключено или API-ключ не задан на backend.")
        if (request.cloudModel !in config.deepseekModels) throw RagFailure("Облачная модель не разрешена конфигурацией сервера.")
        val body = linkedMapOf<String, Any>("model" to request.cloudModel,
            "messages" to messages.map { mapOf("role" to it.role.name.lowercase(), "content" to it.content) },
            "stream" to false,
            "thinking" to mapOf("type" to if (request.thinking) "enabled" else "disabled"))
        request.maxOutputTokens?.let { body["max_tokens"] = it }
        if (request.thinking) body["reasoning_effort"] = "high" else body["temperature"] = request.temperature
        val response = try {
            val httpRequest = HttpRequest.newBuilder(URI.create(config.deepseekBaseUrl.trimEnd('/') + "/chat/completions"))
                .timeout(Duration.ofSeconds(config.cloudTimeoutSeconds)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${config.deepseekApiKey}").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build()
            http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw RagFailure("Облачный запрос прерван.") }
        catch (_: Exception) { throw RagFailure("DeepSeek недоступен или истекло время ожидания. Повтор не выполнялся.") }
        if (response.statusCode() !in 200..299) throw RagFailure("DeepSeek HTTP ${response.statusCode()}. Проверьте ключ, баланс и выбранную модель.")
        val json = try { mapper.readTree(response.body()) } catch (_: Exception) { throw RagFailure("DeepSeek вернул некорректный JSON.") }
        val choice = json.path("choices").firstOrNull() ?: throw RagFailure("Нет choices в ответе DeepSeek.")
        val content = choice.path("message").path("content").takeIf { it.isTextual }?.asText()?.trim()
        val finish = choice.path("finish_reason").asText()
        if (finish !in setOf("stop", "length")) throw RagFailure("DeepSeek не завершил обычную генерацию.")
        if (content == null || content.isBlank() && finish != "length") throw RagFailure("DeepSeek вернул пустой ответ. Возможно, лимит потрачен на reasoning.")
        val reasoning = choice.path("message").path("reasoning_content").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
        val model = json.path("model").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() } ?: throw RagFailure("Нет фактического имени модели DeepSeek.")
        val usage = json.path("usage")
        fun tokens(name: String): Long? {
            if (usage.isMissingNode || usage.isNull) return null
            val count = usage.path(name)
            if (!count.isIntegralNumber || !count.canConvertToLong() || count.asLong() < 0) throw RagFailure("Некорректный usage DeepSeek.")
            return count.asLong()
        }
        val input = tokens("prompt_tokens"); val output = tokens("completion_tokens"); val total = tokens("total_tokens")
        if (total != null && total != input!! + output!!) throw RagFailure("Несогласованный usage DeepSeek.")
        return ModelAnswer(provider, request.cloudModel, model, status = if (finish == "length") RunStatus.TRUNCATED else RunStatus.COMPLETED,
            answer = content, inputTokens = input, outputTokens = output, finishReason = finish, thinking = reasoning)
    }
}
