package dev.aichallenge.local.optimization.evaluation

import dev.aichallenge.local.optimization.models.*
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.core.StreamReadFeature

/** Детерминированно проверяет JSON и пять эталонных полей; не исправляет ответ и не вызывает judge. */
@Component
class CardEvaluator(private val mapper: ObjectMapper) {
    private val strict = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    private val keys = setOf("service", "environment", "priority", "started_at", "root_cause", "summary")
    fun evaluate(answer: String, expected: ExpectedCard?): Evaluation {
        val node = try {
            // readTree может принять первый объект и проигнорировать остаток: проверяем ровно один JSON.
            strict.readValue(answer, Any::class.java)
            strict.readTree(answer)
        } catch (_: Exception) { return Evaluation(false, false, if (expected != null) 0 else null, if (expected != null) 5 else null, if (expected != null) false else null, listOf("Ответ не является одним корректным JSON-объектом.")) }
        val fields = if (node.isObject) node.properties().map { it.key }.toSet() else emptySet()
        val problems = mutableListOf<String>()
        if (!node.isObject || fields != keys) problems += "Нужен объект с ровно шестью ключами по контракту."
        keys.filter { it != "summary" }.forEach { key ->
            if (!node.has(key) || !(node.path(key).isNull || node.path(key).isTextual)) problems += "$key: требуется строка или null."
        }
        if (!node.path("summary").isTextual || node.path("summary").asText().isBlank() || node.path("summary").asText().length > 240) problems += "summary: требуется непустая строка до 240 символов."
        if (!node.path("environment").isNull && node.path("environment").asText() !in setOf("production", "staging", "development")) problems += "environment: неизвестное значение."
        if (!node.path("priority").isNull && node.path("priority").asText() !in setOf("P1", "P2", "P3")) problems += "priority: неизвестное значение."
        val schemaValid = problems.isEmpty()
        val actual = keys.associateWith { key -> if (node.path(key).isNull) null else if (node.path(key).isTextual) node.path(key).asText() else node.path(key).toString() }
        val gold = expected?.let { mapOf("service" to it.service, "environment" to it.environment, "priority" to it.priority, "started_at" to it.started_at, "root_cause" to it.root_cause) }
        val matches = gold?.count { (key, value) -> node.has(key) && actual[key] == value }
        gold?.forEach { (key, value) -> if (!node.has(key) || actual[key] != value) problems += "$key: ожидалось ${value ?: "null"}, получено ${actual[key] ?: "null"}." }
        return Evaluation(true, schemaValid, matches, gold?.size,
            gold?.let { matches == it.size && schemaValid }, problems, actual)
    }
}
