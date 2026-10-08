package dev.aichallenge.local.optimization.models

import dev.aichallenge.local.generation.Generation
import dev.aichallenge.local.generation.LlmMessage
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import java.util.UUID

/** Провайдер выбирается явно; облако не является запасным вариантом при ошибке Ollama. */
enum class Provider { LOCAL, DEEPSEEK }
enum class PromptProfile { BASELINE, SPECIALIZED }
enum class ExperimentStatus { RUNNING, COMPLETED, PARTIAL, FAILED, INTERRUPTED }
enum class ItemStatus { PENDING, RUNNING, COMPLETED, TRUNCATED, FAILED, INTERRUPTED }

/** Общие настройки; contextSize/topK/seed передаются только Ollama, что отмечается в результатах. */
data class Parameters(
    @field:DecimalMin("0.0") @field:DecimalMax("2.0") val temperature: Double = 0.7,
    @field:Min(64) @field:Max(8192) val maxTokens: Int = 512,
    @field:Min(1024) @field:Max(16384) val contextSize: Int = 4096,
    val thinking: Boolean = false,
    @field:DecimalMin("0.01") @field:DecimalMax("1.0") val topP: Double = 0.8,
    @field:Min(1) @field:Max(100) val topK: Int = 20,
    val seed: Int = 42,
)

/** UUID обеспечивает повторное чтение после потери HTTP-ответа, но не автоматическую повторную генерацию. */
data class ExperimentRequest(
    val id: UUID = UUID.randomUUID(),
    val provider: Provider = Provider.LOCAL,
    val cloudModel: String = "deepseek-v4-pro",
    val promptProfile: PromptProfile = PromptProfile.BASELINE,
    val skillEnabled: Boolean = false,
    @field:Valid val parameters: Parameters = Parameters(),
    @field:Size(max = 20) val caseIds: List<String> = emptyList(),
    @field:Size(max = 16000) val input: String = "",
    @field:Min(1) @field:Max(3) val repetitions: Int = 1,
)

/** Эталон известных полей. Свободное резюме оценивается человеком, а не сравнением строк. */
data class ExpectedCard(val service: String?, val environment: String?, val priority: String?, val started_at: String?, val root_cause: String?)
data class BenchmarkCase(val id: String, val title: String, val split: String, val input: String, val expected: ExpectedCard)
data class SkillInfo(val id: String, val version: String, val title: String, val sha256: String)
data class ModelIdentity(val name: String, val digest: String, val quantization: String?, val parameterSize: String?, val fileBytes: Long?)
data class Settings(val localModel: String, val runtimeConnected: Boolean, val installed: Boolean, val identity: ModelIdentity?,
    val cloudEnabled: Boolean, val cloudReady: Boolean, val cloudModels: List<String>, val skill: SkillInfo)

/** sampled peak относится к выделению выбранной модели в Ollama, не к RSS процесса или всей видеокарте. */
data class ResourceMetrics(val samples: Int = 0, val peakAllocatedBytes: Long? = null, val peakVramBytes: Long? = null,
    val peakNonVramBytes: Long? = null, val observedContextSize: Int? = null, val placement: String? = null,
    val source: String = "Ollama /api/ps; sampled model allocation, not process RSS")

/** Проверка синтаксиса/схемы отделена от фактической точности полей и успешного HTTP-вызова. */
data class Evaluation(val validJson: Boolean, val validSchema: Boolean, val matchedFields: Int?, val totalFields: Int?,
    val exactFields: Boolean?, val mismatches: List<String>, val card: Map<String, Any?>? = null)
data class ExperimentItem(val id: String, val caseId: String?, val title: String, val repetition: Int, val input: String,
    val expected: ExpectedCard?, val messages: List<LlmMessage>, val promptSha256: String,
    val status: ItemStatus = ItemStatus.PENDING, val generation: Generation? = null, val elapsedMilliseconds: Long? = null,
    val resources: ResourceMetrics? = null, val evaluation: Evaluation? = null, val error: String? = null)
data class Experiment(val id: String, val createdAt: String, val request: ExperimentRequest, val model: String,
    val identity: ModelIdentity?, val runtimeVersion: String?, val promptVersion: String, val skill: SkillInfo?,
    val status: ExperimentStatus, val items: List<ExperimentItem>, val note: String = "")
data class ExperimentSummary(val id: String, val createdAt: String, val provider: Provider, val model: String,
    val profile: PromptProfile, val skillEnabled: Boolean, val status: ExperimentStatus, val finished: Int, val total: Int)
class OptimizationFailure(message: String) : RuntimeException(message)
class OptimizationMissing : RuntimeException("Эксперимент не найден.")
