package dev.aichallenge.local.rag.embeddings

import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.rag.config.RagProperties
import dev.aichallenge.local.rag.models.RagFailure
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.*
import java.time.Duration
import kotlin.math.sqrt

data class EmbeddingBatch(val vectors: List<List<Double>>, val inputTokens: Long?)
/** Порт локальных embeddings, заменяемый в тестах без загрузки реальной модели. */
interface EmbeddingEngine { fun digest(): String; fun embed(texts: List<String>): EmbeddingBatch }

/** Только локальная Ollama: реальные вектора, запрет cloud-тегов и тихого truncation. */
@Component
class OllamaEmbeddings(private val local: LabProperties, private val config: RagProperties, private val mapper: ObjectMapper) : EmbeddingEngine {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    override fun digest(): String {
        val model = call("/api/tags").path("models").firstOrNull { it.path("name").asText() == config.embeddingModel }
            ?: throw RagFailure("Загрузите локально ${config.embeddingModel}: ollama pull ${config.embeddingModel}.")
        if (model.hasNonNull("remote_host") || model.hasNonNull("remote_model")) throw RagFailure("Облачная embedding-модель запрещена.")
        return model.path("digest").asText().takeIf { it.isNotBlank() } ?: throw RagFailure("Нет digest embedding-модели.")
    }
    override fun embed(texts: List<String>): EmbeddingBatch {
        val result = call("/api/embed", mapOf("model" to config.embeddingModel, "input" to texts, "truncate" to false, "keep_alive" to "5m"))
        if (result.path("model").asText() != config.embeddingModel) throw RagFailure("Ollama вернула другую embedding-модель.")
        val nodes = result.path("embeddings")
        if (!nodes.isArray || nodes.size() != texts.size) throw RagFailure("Неверное число embedding-векторов.")
        val vectors = nodes.toList().map { vector ->
            if (!vector.isArray || vector.isEmpty) throw RagFailure("Пустой embedding-вектор.")
            vector.toList().map { coordinate -> if (!coordinate.isNumber) throw RagFailure("Нечисловой embedding."); coordinate.asDouble() }
                .also { validateVector(it) }
        }
        if (vectors.map { it.size }.distinct().size != 1) throw RagFailure("Размерности embedding-векторов различаются.")
        return EmbeddingBatch(vectors, result.path("prompt_eval_count").takeIf { it.isIntegralNumber && it.asLong() >= 0 }?.asLong())
    }
    private fun call(path: String, body: Any? = null): tools.jackson.databind.JsonNode {
        try {
            val builder = HttpRequest.newBuilder(URI.create(local.baseUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(180))
            if (body == null) builder.GET() else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) throw RagFailure("Ollama embeddings HTTP ${response.statusCode()}. Проверьте модель и размер текста.")
            return mapper.readTree(response.body())
        } catch (e: RagFailure) { throw e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw RagFailure("Embeddings прерваны.") }
        catch (_: Exception) { throw RagFailure("Локальный embedding-runtime недоступен или не успел ответить.") }
    }
}
fun validateVector(vector: List<Double>) {
    if (vector.isEmpty() || vector.any { !it.isFinite() } || vector.sumOf { it * it } <= 0) throw RagFailure("Нулевой или некорректный embedding-вектор.")
}
/** Exact cosine: similarity не является вероятностью правильного ответа. */
fun cosine(a: List<Double>, b: List<Double>): Double {
    validateVector(a); validateVector(b)
    if (a.size != b.size) throw RagFailure("Embedding space не совпадает с индексом.")
    return a.indices.sumOf { a[it] * b[it] } / sqrt(a.sumOf { it * it } * b.sumOf { it * it })
}
