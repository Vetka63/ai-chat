package dev.aichallenge.rag.embeddings.adapters

import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.embeddings.ports.*
import dev.aichallenge.rag.indexing.models.EmbeddingIdentity
import dev.aichallenge.rag.retrieval.services.VectorMath
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.*
import java.time.Duration

/** Вызывает только локальный /api/embed, запрещает тихое truncation и проверяет каждую координату. */
@Component
class OllamaEmbeddingProvider(private val properties: RagProperties, private val mapper: ObjectMapper) : EmbeddingProvider {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    override fun identity(): EmbeddingIdentity {
        val tags = request("/api/tags", null)
        val models = tags.path("models")
        val tag = models.firstOrNull { it.path("name").asText() == properties.embeddingModel || it.path("model").asText() == properties.embeddingModel }
            ?: throw LabException("model_missing", "Модель ${properties.embeddingModel} не загружена. Выполните ollama pull.", HttpStatus.SERVICE_UNAVAILABLE)
        if (tag.path("digest").asText().isBlank()) throw LabException("embedding_identity", "Ollama не вернула digest модели.", HttpStatus.BAD_GATEWAY)
        val probe = embed(listOf("Проверка размерности векторов русской Pro Git."))
        return EmbeddingIdentity(properties.embeddingModel, tag.path("digest").asText(), probe.vectors.first().size)
    }
    override fun embed(texts: List<String>): EmbeddingBatch {
        require(texts.isNotEmpty())
        val started = System.nanoTime()
        val result = request("/api/embed", mapper.writeValueAsString(mapOf("model" to properties.embeddingModel, "input" to texts, "truncate" to false)))
        if (result.path("model").asText() != properties.embeddingModel) throw LabException("embedding_model_mismatch", "Ollama вернула другую модель.", HttpStatus.BAD_GATEWAY)
        val embeddings = result.path("embeddings")
        if (!embeddings.isArray || embeddings.size() != texts.size) throw LabException("embedding_count", "Ollama вернула неверное количество векторов.", HttpStatus.BAD_GATEWAY)
        val vectors = (0 until embeddings.size()).map { index ->
            val vector = embeddings[index]
            if (!vector.isArray || vector.isEmpty) throw LabException("embedding_invalid", "Пустой embedding-вектор.", HttpStatus.BAD_GATEWAY)
            val values = FloatArray(vector.size()) { coordinate ->
                val n = vector[coordinate]
                if (!n.isNumber) throw LabException("embedding_invalid", "Координата вектора не является числом.", HttpStatus.BAD_GATEWAY)
                n.asDouble().toFloat()
            }
            try { VectorMath.validate(values) } catch (error: IllegalArgumentException) {
                throw LabException("embedding_invalid", "Ollama вернула нулевой или нечисловой вектор.", HttpStatus.BAD_GATEWAY)
            }
            values
        }
        if (vectors.map { it.size }.distinct().size != 1) throw LabException("embedding_dimension", "Векторы batch имеют разную размерность.", HttpStatus.BAD_GATEWAY)
        val usage = result.path("prompt_eval_count").takeIf { it.isNumber }?.asLong()
        return EmbeddingBatch(vectors, usage, (System.nanoTime() - started) / 1_000_000)
    }
    private fun request(path: String, body: String?): JsonNode {
        val builder = HttpRequest.newBuilder(URI.create(properties.ollamaUrl.trimEnd('/') + path)).timeout(Duration.ofSeconds(properties.embedTimeoutSeconds))
        if (body == null) builder.GET() else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        val response = try { client.send(builder.build(), HttpResponse.BodyHandlers.ofString()) } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw LabException("ollama_unavailable", "Ollama недоступна или не успела ответить. Проверьте embedding-runtime и повторите.", HttpStatus.SERVICE_UNAVAILABLE)
        }
        if (response.statusCode() !in 200..299) throw LabException("ollama_error", "Ollama HTTP ${response.statusCode()}: ${response.body().take(350)}", HttpStatus.BAD_GATEWAY)
        return try { mapper.readTree(response.body()) } catch (error: Exception) {
            throw LabException("ollama_invalid_json", "Ollama вернула некорректный JSON.", HttpStatus.BAD_GATEWAY)
        }
    }
}
