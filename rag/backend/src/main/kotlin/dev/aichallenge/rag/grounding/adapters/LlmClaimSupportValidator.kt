package dev.aichallenge.rag.grounding.adapters

import dev.aichallenge.rag.answering.ports.LlmClient
import dev.aichallenge.rag.answering.services.CostEstimator
import dev.aichallenge.rag.grounding.enums.ClaimSupportVerdict
import dev.aichallenge.rag.grounding.enums.SupportCheckStatus
import dev.aichallenge.rag.grounding.models.*
import dev.aichallenge.rag.grounding.ports.ClaimSupportValidator
import dev.aichallenge.rag.grounding.services.ClaimSupportPromptAssembler
import dev.aichallenge.rag.grounding.services.ClaimScopePromptAssembler
import dev.aichallenge.rag.grounding.services.SourceScopeInspector
import dev.aichallenge.rag.answering.models.LlmMessage
import dev.aichallenge.rag.retrieval.models.SearchHit
import org.springframework.stereotype.Component
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Проверка фактов и отдельный двухшаговый анализ условий; технические ошибки не повторяются. */
@Component
class LlmClaimSupportValidator(private val llm: LlmClient, private val prompt: ClaimSupportPromptAssembler, private val mapper: ObjectMapper, private val costs: CostEstimator) : ClaimSupportValidator {
    override fun validate(claims: List<GroundedClaim>, included: List<SearchHit>): ClaimSupportCheck {
        return validateScoped(claims, included, null)
    }
    override fun validateScoped(claims: List<GroundedClaim>, included: List<SearchHit>, question: String?): ClaimSupportCheck {
        val messages = prompt.assemble(claims, included, question)
        return check(claims, included, messages)
    }
    override fun validateScope(claims: List<GroundedClaim>, included: List<SearchHit>, question: String?): ClaimSupportCheck {
        val sourceMessages = SourceScopeInspector.messages(claims, included, mapper)
        val sourceResponse = llm.completeVerifiedJson(sourceMessages, 16384)
        val sourceGeneration = GroundingGeneration(sourceResponse.model, sourceResponse.finishReason, sourceResponse.milliseconds, sourceResponse.usage, costs.estimate(sourceResponse.model, sourceResponse.usage), sourceMessages, sourceResponse.content)
        val bindings = if (sourceResponse.finishReason == "stop") SourceScopeInspector.parse(sourceResponse.content, claims, included, mapper) else null
        if (bindings == null) return ClaimSupportCheck(SupportCheckStatus.INVALID_RESPONSE, emptyList(), listOf(EvidenceIssue("invalid_source_scope", "Не удалось выделить область действия источника. Ответ не опубликован.")), sourceGeneration)
        val requirements = ClaimScopePromptAssembler.requirements(bindings, included)
        val messages = ClaimScopePromptAssembler.assemble(claims, requirements, question, mapper)
        return try {
            val response = llm.completeScopeJson(messages, 16384)
            val generation = GroundingGeneration(response.model, response.finishReason, response.milliseconds, response.usage, costs.estimate(response.model, response.usage), messages, response.content)
            val verdicts = if (response.finishReason == "stop") ClaimScopePromptAssembler.parse(response.content, requirements, claims, question, mapper) else null
            if (verdicts == null) ClaimSupportCheck(SupportCheckStatus.INVALID_RESPONSE, emptyList(), listOf(EvidenceIssue("invalid_scope_checks", "Не получены однозначные проверки всех условий источника.")), sourceGeneration, listOf(generation))
            else {
                val assessments = claims.indices.map { index ->
                    val missing = verdicts.filter { it.requirement.claimIndex == index && !it.preserved }
                    ClaimSupportAssessment(index, if (missing.isEmpty()) ClaimSupportVerdict.SUPPORTED else ClaimSupportVerdict.UNSUPPORTED,
                        if (missing.isEmpty()) "Область действия и условия источника сохранены." else missing.joinToString("; ") { it.reason }.take(1000))
                }
                val issues = assessments.filter { it.verdict != ClaimSupportVerdict.SUPPORTED }.map { EvidenceIssue("unsupported_claim", "Потеряно условие или область действия источника.", it.claimIndex) }
                ClaimSupportCheck(if (issues.isEmpty()) SupportCheckStatus.PASSED else SupportCheckStatus.REJECTED, assessments, issues, sourceGeneration, listOf(generation))
            }
        } catch (_: Exception) {
            // Второй вызов имеет неизвестный usage, первый нельзя терять при транспортной ошибке.
            val failed = GroundingGeneration(sourceResponse.model, "error", 0, null, null, emptyList(), "")
            ClaimSupportCheck(SupportCheckStatus.INVALID_RESPONSE, emptyList(), listOf(EvidenceIssue("scope_check_unavailable", "Сравнение условий недоступно; ответ не опубликован.")), sourceGeneration, listOf(failed))
        }
    }

    private fun check(claims: List<GroundedClaim>, included: List<SearchHit>, messages: List<LlmMessage>): ClaimSupportCheck {
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
            val required = setOf("claim_index", "conditions", "verdict", "reason", "evidence_scope", "claim_scope")
            val validShape = node.isObject && required.all { node.has(it) } && node.size() == required.size + (if (node.has("text")) 1 else 0) + (if (node.has("citations")) 1 else 0)
            if (!validShape || !indexNode.isIntegralNumber || !indexNode.canConvertToInt() || indexNode.asInt() !in claims.indices || !seen.add(indexNode.asInt()) || verdict == null || reason.isNullOrBlank() || reason.length > 1000 || evidenceScope !in setOf("general", "example") || claimScope !in setOf("general", "example")) return invalid("invalid_support_shape", "Вердикты проверки неполны, неоднозначны или имеют неверную форму.")
            // Допустимо только дословное эхо исходного claim; оно не заменяет и не исправляет его.
            if (node.has("text") && (!node.path("text").isString || node.path("text").asText() != claims[indexNode.asInt()].text)) return invalid("invalid_support_shape", "Текст в вердикте не совпадает с исходным пунктом ответа.")
            if (node.has("citations")) {
                val original = mapper.valueToTree<JsonNode>(claims[indexNode.asInt()].citations.map { mapOf("chunk_id" to it.source.chunkId, "quote" to it.quote) })
                if (node.path("citations") != original) return invalid("invalid_support_shape", "Эхо доказательств не совпало с исходными цитатами.")
            }
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
                val conditionFields = arrayOf("chunk_id", "span_index", "preserved")
                if (!shape(condition, *conditionFields) || id !in ownSources || hit == null || !span.isIntegralNumber || !span.canConvertToInt() || span.asInt() !in ClaimSupportPromptAssembler.passages(hit.chunk.text).indices || !preserved.isBoolean || !seenConditions.add(id!! to span.asInt())) return invalid("invalid_support_conditions", "Предпосылка не совпала с цитируемым контекстом или имеет неверную форму.")
                if (!preserved.asBoolean()) missingCondition = true
            }
            // Частный пример не подтверждает обобщение даже при положительном вердикте модели.
            val sourceIsExample = evidenceScope == "example"
            val scopeMismatch = verdict == ClaimSupportVerdict.SUPPORTED && sourceIsExample && claimScope == "general"
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
            EvidenceIssue(if (it.verdict == ClaimSupportVerdict.CONTRADICTED) "contradicted_claim" else "unsupported_claim", "Цитаты не подтверждают полный смысл этого пункта. Ответ не опубликован.", it.claimIndex)
        }
        return ClaimSupportCheck(if (issues.isEmpty()) SupportCheckStatus.PASSED else SupportCheckStatus.REJECTED, ordered, issues, generation)
    }

    private fun shape(node: JsonNode, vararg fields: String) = node.isObject && node.size() == fields.size && fields.all { node.has(it) }
}
