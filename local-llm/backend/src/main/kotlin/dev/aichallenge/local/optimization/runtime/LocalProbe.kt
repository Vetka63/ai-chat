package dev.aichallenge.local.optimization.runtime

import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.optimization.models.*
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.*
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Читает метаданные и выделение памяти локальной модели; не загружает веса и не генерирует текст. */
@Component
class LocalProbe(private val config: LabProperties, private val mapper: ObjectMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private fun get(path: String): JsonNode {
        val response = http.send(HttpRequest.newBuilder(URI.create(config.baseUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw OptimizationFailure("Метаданные Ollama недоступны.")
        return mapper.readTree(response.body())
    }
    fun identity(name: String): ModelIdentity? = get("/api/tags").path("models").firstOrNull {
        it.path("name").asText() == name && !it.hasNonNull("remote_host") && !it.hasNonNull("remote_model")
    }?.let { node ->
        if (!node.path("digest").isTextual || node.path("digest").asText().isBlank()) throw OptimizationFailure("Ollama не вернула digest модели.")
        ModelIdentity(name, node.path("digest").asText(), node.path("details").text("quantization_level"),
            node.path("details").text("parameter_size"), if (node.path("size").isNumber) node.path("size").asLong() else null)
    }
    private fun JsonNode.text(field: String): String? = if (path(field).isTextual) path(field).asText() else null

    /** Семплирует /api/ps; отсутствие telemetry не превращает успешную генерацию в ошибку. */
    fun sample(name: String): ResourceMetrics? = try {
        get("/api/ps").path("models").firstOrNull { it.path("name").asText() == name }?.let { node ->
            val size = if (node.path("size").isNumber) node.path("size").asLong() else null
            val vram = if (node.path("size_vram").isNumber) node.path("size_vram").asLong() else null
            ResourceMetrics(1, size, vram, if (size != null && vram != null) (size - vram).coerceAtLeast(0) else null,
                if (node.path("context_length").isNumber) node.path("context_length").asInt() else null,
                if (size == null || vram == null) null else if (vram >= size) "GPU" else if (vram == 0L) "CPU" else "CPU/GPU")
        }
    } catch (_: Exception) { null }

    /** При перенастройке окна не приписывает старое выделение предыдущему окну нового запроса. */
    fun monitor(name: String, contextSize: Int) = AllocationMonitor {
        sample(name)?.takeIf { it.observedContextSize == contextSize }?.copy(
            source = "Ollama /api/ps; sampled allocation at requested context=$contextSize; other window configurations excluded; not RSS")
    }
}

/** Изолированный sampler одного вызова; хранит sampled maximum, а не обещанный физический peak. */
class AllocationMonitor(private val sample: () -> ResourceMetrics?) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "ollama-allocation-sampler").also { it.isDaemon = true } }
    private var metrics = ResourceMetrics()
    init { executor.scheduleWithFixedDelay({ collect() }, 0, 250, TimeUnit.MILLISECONDS) }
    @Synchronized private fun collect() { sample()?.let { next ->
        fun peak(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else maxOf(a, b)
        metrics = metrics.copy(samples = metrics.samples + 1, peakAllocatedBytes = peak(metrics.peakAllocatedBytes, next.peakAllocatedBytes),
            peakVramBytes = peak(metrics.peakVramBytes, next.peakVramBytes), peakNonVramBytes = peak(metrics.peakNonVramBytes, next.peakNonVramBytes),
            observedContextSize = next.observedContextSize ?: metrics.observedContextSize, placement = next.placement ?: metrics.placement, source = next.source)
    } }
    override fun close() { executor.shutdownNow(); collect() }
    @Synchronized fun result() = metrics
}
