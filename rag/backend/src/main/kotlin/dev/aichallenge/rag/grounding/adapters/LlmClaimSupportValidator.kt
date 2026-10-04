package dev.aichallenge.rag.grounding.adapters

import dev.aichallenge.rag.answering.ports.LlmClient
import dev.aichallenge.rag.answering.services.CostEstimator
import dev.aichallenge.rag.grounding.enums.ClaimSupportVerdict
import dev.aichallenge.rag.grounding.enums.SupportCheckStatus
import dev.aichallenge.rag.grounding.models.*
import dev.aichallenge.rag.grounding.ports.ClaimSupportValidator
import dev.aichallenge.rag.grounding.services.ClaimSupportPromptAssembler
import dev.aichallenge.rag.retrieval.models.SearchHit
import org.springframework.stereotype.Component
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Один ограниченный вызов проверки; неверный или отсутствующий вердикт закрывает ответ без повторного запроса. */
@Component
class LlmClaimSupportValidator(private val llm: LlmClient, private val prompt: ClaimSupportPromptAssembler, private val mapper: ObjectMapper, private val costs: CostEstimator) : ClaimSupportValidator {
    override fun validate(claims: List<GroundedClaim>, included: List<SearchHit>): ClaimSupportCheck {
        val messages = prompt.assemble(claims, included)
        // Технический лимит только проверяющей модели; пользовательский лимит генерации не меняется.
        val response = llm.completeVerifiedJson(messages, 16384)
        val generation = GroundingGeneration(response.model, response.finishReason, response.milliseconds, response.usage, costs.estimate(response.model, response.usage), messages, response.content)
        fun invalid(code: String, message: String) = ClaimSupportCheck(SupportCheckStatus.INVALID_RESPONSE, emptyList(), listOf(EvidenceIssue(code, message)), generation)
        if (response.finishReason != "stop") return invalid("truncated_support_check", "Проверка смысла не завершена. Ответ не опубликован; автоматического повтора нет.")
        if (response.content.length > 60000) return invalid("invalid_support_shape", "Ответ проверки смысла слишком большой.")
        val root = try { mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(response.content) } catch (_: Exception) { return invalid("invalid_support_json", "Проверка смысла вернула некорректный JSON.") }
        if (root == null || !shape(root, "claims") || !root.path("claims").isArray || root.path("claims").size() != claims.size) return invalid("invalid_support_shape", "Нужен отдельный вердикт для каждого пункта ответа.")
        val assessments = mutableListOf<ClaimSupportAssessment>()
        val seen = mutableSetOf<Int>()
        for (node in root.path("claims")) {
            val indexNode = node.path("claim_index")
            val reason = node.path("reason").takeIf { it.isString }?.asText()?.trim()
            val verdict = when (node.path("verdict").takeIf { it.isString }?.asText()) {
                "supported" -> ClaimSupportVerdict.SUPPORTED
                "unsupported" -> ClaimSupportVerdict.UNSUPPORTED
                "contradicted" -> ClaimSupportVerdict.CONTRADICTED
                else -> null
            }
            val evidenceScope = node.path("evidence_scope").takeIf { it.isString }?.asText()
            val claimScope = node.path("claim_scope").takeIf { it.isString }?.asText()
            val validShape = shape(node, "claim_index", "conditions", "verdict", "reason", "evidence_scope", "claim_scope") || shape(node, "claim_index", "conditions", "verdict", "reason", "evidence_scope", "claim_scope", "text")
            if (!validShape || !indexNode.isIntegralNumber || !indexNode.canConvertToInt() || indexNode.asInt() !in claims.indices || !seen.add(indexNode.asInt()) || verdict == null || reason.isNullOrBlank() || reason.length > 300 || evidenceScope !in setOf("general", "example") || claimScope !in setOf("general", "example")) return invalid("invalid_support_shape", "Вердикты проверки неполны, неоднозначны или имеют неверную форму.")
            // Допустимо только дословное эхо исходного claim; оно не заменяет и не исправляет его.
            if (node.has("text") && (!node.path("text").isString || node.path("text").asText() != claims[indexNode.asInt()].text)) return invalid("invalid_support_shape", "Текст в вердикте не совпадает с исходным пунктом ответа.")
            // Предпосылки не служат новым доказательством, но отрицательная оценка не может сопровождаться допуском.
            val conditions = node.path("conditions")
            if (!conditions.isArray || conditions.size() > 8) return invalid("invalid_support_conditions", "Нужен ограниченный список проверенных предпосылок.")
            val ownSources = claims[indexNode.asInt()].citations.map { it.source.chunkId }.toSet()
            var missingCondition = false
            val seenConditions = mutableSetOf<Pair<String, Int>>()
            for (condition in conditions) {
                val id = condition.path("chunk_id").takeIf { it.isString }?.asText()
                val span = condition.path("span_index")
                val preserved = condition.path("preserved")
                val hit = included.firstOrNull { it.chunk.chunkId == id }
                if (!shape(condition, "chunk_id", "span_index", "preserved") || id !in ownSources || hit == null || !span.isIntegralNumber || !span.canConvertToInt() || span.asInt() !in ClaimSupportPromptAssembler.passages(hit.chunk.text).indices || !preserved.isBoolean || !seenConditions.add(id!! to span.asInt())) return invalid("invalid_support_conditions", "Предпосылка не совпала с цитируемым контекстом или имеет неверную форму.")
                if (!preserved.asBoolean()) missingCondition = true
            }
            // Частный пример не подтверждает обобщение даже при положительном вердикте модели.
            val scopeMismatch = verdict == ClaimSupportVerdict.SUPPORTED && evidenceScope == "example" && claimScope == "general"
            val conditionMismatch = verdict == ClaimSupportVerdict.SUPPORTED && missingCondition
            assessments.add(ClaimSupportAssessment(indexNode.asInt(), if (scopeMismatch || conditionMismatch) ClaimSupportVerdict.UNSUPPORTED else verdict, when {
                scopeMismatch -> "Обобщён частный пример. $reason".take(300)
                conditionMismatch -> "Потеряно условие источника. $reason".take(300)
                else -> reason
            }))
        }
        if (seen != claims.indices.toSet()) return invalid("invalid_support_shape", "Проверка смысла пропустила пункт ответа.")
        val ordered = assessments.sortedBy { it.claimIndex }
        val issues = ordered.filter { it.verdict != ClaimSupportVerdict.SUPPORTED }.map {
            EvidenceIssue(if (it.verdict == ClaimSupportVerdict.CONTRADICTED) "contradicted_claim" else "unsupported_claim", "Цитаты не подтверждают полный смысл пункта ${it.claimIndex + 1}. Ответ не опубликован.", it.claimIndex)
        }
        return ClaimSupportCheck(if (issues.isEmpty()) SupportCheckStatus.PASSED else SupportCheckStatus.REJECTED, ordered, issues, generation)
    }

    private fun shape(node: JsonNode, vararg fields: String) = node.isObject && node.size() == fields.size && fields.all { node.has(it) }
}
