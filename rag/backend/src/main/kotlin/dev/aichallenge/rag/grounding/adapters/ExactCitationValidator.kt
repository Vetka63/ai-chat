package dev.aichallenge.rag.grounding.adapters

import dev.aichallenge.rag.grounding.enums.GroundedStatus
import dev.aichallenge.rag.grounding.models.*
import dev.aichallenge.rag.grounding.ports.CitationValidator
import dev.aichallenge.rag.retrieval.models.SearchHit
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Fail-closed проверка всей структуры и точных цитат; metadata и координаты не доверяются модели. */
@Component
class ExactCitationValidator(private val mapper: ObjectMapper) : CitationValidator {
    /** Не принимает обрезанный JSON, неизвестный ID, paraphrase вместо цитаты или пункт без evidence. */
    override fun validate(content: String, finishReason: String, included: List<SearchHit>): EvidenceValidation {
        if (finishReason != "stop") return invalid("truncated_evidence", "Структурированный ответ не завершён. Увеличьте лимит ответа или оставьте его пустым.")
        if (content.length > 100000) return invalid("invalid_evidence_shape", "Структурированный ответ слишком большой.")
        val root = try { mapper.readTree(content) } catch (_: Exception) { return invalid("invalid_evidence_json", "Модель вернула некорректный JSON.") }
        if (root == null || !(shape(root, "status", "claims", "clarification") || shape(root, "status", "claims")) || !root.path("claims").isArray) return invalid("invalid_evidence_shape", "Нужны status и claims, только допустимое поле clarification, без дополнительных полей.")
        val status = root.path("status").takeIf { it.isString }?.asText()
        val nodes = root.path("claims").toList()
        if (status == "unknown") {
            val clarify = root.path("clarification").takeIf { it.isString }?.asText()?.trim()
            if (nodes.isNotEmpty() || clarify.isNullOrBlank() || clarify.length > 500) return invalid("invalid_unknown", "Для unknown нужны пустые claims и просьба уточнить вопрос.")
            return EvidenceValidation(GroundedStatus.UNKNOWN, clarification = clarify)
        }
        // Отсутствующее nullable поле при known не меняет evidence; unknown выше требует строку.
        if (status != "known" || nodes.size !in 1..8 || root.has("clarification") && !root.path("clarification").isNull) return invalid("invalid_evidence_shape", "Для known нужны 1–8 пунктов; clarification, если задано, должно быть null.")
        val actual = included.associateBy { it.chunk.chunkId }
        val claims = mutableListOf<GroundedClaim>()
        val issues = mutableListOf<EvidenceIssue>()
        nodes.forEachIndexed { i, node ->
            val text = node.path("text").takeIf { it.isString }?.asText()?.trim()
            val refs = node.path("citations")
            if (!shape(node, "text", "citations") || text.isNullOrBlank() || text.length > 1500 || !refs.isArray || refs.size() !in 1..3) {
                issues.add(EvidenceIssue("uncited_claim", "Каждый пункт должен иметь текст и 1–3 цитаты.", i)); return@forEachIndexed
            }
            val citations = mutableListOf<VerifiedCitation>()
            refs.forEachIndexed { j, ref ->
                val id = ref.path("chunk_id").takeIf { it.isString }?.asText()
                val quote = ref.path("quote").takeIf { it.isString }?.asText()
                val hit = actual[id]
                if (!shape(ref, "chunk_id", "quote") || quote == null || quote.isBlank() || quote.length !in 20..600) {
                    issues.add(EvidenceIssue("invalid_citation_shape", "Цитата должна содержать chunk_id и 20–600 точных символов текста.", i, j))
                } else if (hit == null) {
                    issues.add(EvidenceIssue("unknown_evidence_id", "Цитата ссылается не на фактически переданный чанк.", i, j))
                } else {
                    val offset = hit.chunk.text.indexOf(quote)
                    if (offset < 0) issues.add(EvidenceIssue("quote_not_exact", "Цитата не является точным непрерывным фрагментом чанка.", i, j))
                    else {
                        val c = hit.chunk
                        val source = EvidenceSource(c.chunkId, c.documentId, c.source, c.title, c.section)
                        citations.add(VerifiedCitation(source, quote, offset, offset + quote.length, c.start + offset, c.start + offset + quote.length))
                    }
                }
            }
            claims.add(GroundedClaim(text!!, citations))
        }
        if (issues.isNotEmpty()) return EvidenceValidation(GroundedStatus.INVALID_EVIDENCE, issues = issues)
        return EvidenceValidation(GroundedStatus.ANSWERED, claims, claims.flatMap { it.citations }.map { it.source }.distinctBy { it.chunkId })
    }
    /** Точная форма не позволяет модели прислать собственную metadata или отдельный свободный answer. */
    private fun shape(node: JsonNode, vararg fields: String) = node.isObject && node.size() == fields.size && fields.all { node.has(it) }
    private fun invalid(code: String, message: String) = EvidenceValidation(GroundedStatus.INVALID_EVIDENCE, issues = listOf(EvidenceIssue(code, message)))
}
