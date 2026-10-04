package dev.aichallenge.rag

import dev.aichallenge.rag.grounding.models.GroundedClaim
import dev.aichallenge.rag.grounding.services.SourceScopeInspector
import dev.aichallenge.rag.grounding.services.ClaimSupportPromptAssembler
import dev.aichallenge.rag.grounding.services.ClaimScopePromptAssembler
import dev.aichallenge.rag.grounding.adapters.LlmClaimSupportValidator
import dev.aichallenge.rag.grounding.enums.SupportCheckStatus
import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.answering.ports.LlmClient
import dev.aichallenge.rag.answering.services.CostEstimator
import dev.aichallenge.rag.retrieval.models.SearchHit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/** Контракт независимой разметки; эти тесты не подменяют оценку смысловой точности LLM. */
class SourceScopeTest {
    private val mapper = jacksonObjectMapper()
    private val fixture = javaClass.getResourceAsStream("/grounding/merge-missing-divergence-regression.json").use { mapper.readTree(it) }
    private val claims = fixture.path("claims").toList().map { mapper.treeToValue(it, GroundedClaim::class.java) }
    private val hits = fixture.path("included").toList().map { mapper.treeToValue(it, SearchHit::class.java) }
    private val id = hits.single().chunk.chunkId
    private fun raw(scope: String = "example", spans: List<Int> = listOf(1), chunk: String = id, index: Int = 0) = mapper.writeValueAsString(mapOf("bindings" to listOf(mapOf("claim_index" to index, "chunk_id" to chunk, "scope" to scope, "premise_spans" to spans))))
    @Test fun `source pass does not see generated answer or user question`() {
        assertTrue(SourceScopeInspector.system.contains("json", ignoreCase = true))
        assertTrue(ClaimScopePromptAssembler.system.contains("json", ignoreCase = true)) // Требование API для response_format=json_object.
        val messages = SourceScopeInspector.messages(claims.map { it.copy(text = "PRIVATE GENERATED ANSWER") }, hits, mapper)
        assertFalse(messages.last().content.contains("PRIVATE GENERATED ANSWER"))
        val payload = mapper.readTree(messages.last().content)
        assertFalse(payload.has("question"))
        assertEquals(1, payload.path("source_context").size())
        assertTrue(payload.path("items")[0].has("quotes"))
        val repeated = mapper.readTree(SourceScopeInspector.messages(claims + claims, hits, mapper).last().content)
        assertEquals(2, repeated.path("items").size())
        assertEquals(1, repeated.path("source_context").size())
    }
    @Test fun `bindings cover own sources and existing paragraphs only`() {
        assertEquals(listOf(1), SourceScopeInspector.parse(raw(), claims, hits, mapper)!!.single().premiseSpans)
        assertNotNull(SourceScopeInspector.parse(raw("general", emptyList()), claims, hits, mapper))
        for (invalid in listOf(raw(spans = emptyList()), raw(spans = listOf(-1)), raw(spans = listOf(999)), raw(spans = listOf(1, 1)), raw(chunk = "foreign"), raw(index = 1), raw(scope = "unknown"), "{\"bindings\":[]}", raw() + raw(), "{\"bindings\":[],\"bindings\":[]}")) {
            assertNull(SourceScopeInspector.parse(invalid, claims, hits, mapper), invalid)
        }
    }
    @Test fun `server enumerated scope and premise checks are mandatory and every call is counted`() {
        fun check(conditions: List<Any>, first: String = raw(), failSecond: Boolean = false): Pair<dev.aichallenge.rag.grounding.models.ClaimSupportCheck, Int> {
            var calls = 0
            val llm = object : LlmClient {
                override fun settings() = AnswerSettings(true, "deepseek-flash", 0.0, "disabled", 16000, null, "")
                override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion {
                    calls++
                    if (calls == 2 && failSecond) error("offline failure")
                    val content = if (calls == 1) first else mapper.writeValueAsString(mapOf("checks" to conditions))
                    return LlmCompletion("fixture", "deepseek-v4-pro", content, "stop", 1, TokenUsage(10, 10, 20, 0, 10))
                }
            }
            return LlmClaimSupportValidator(llm, ClaimSupportPromptAssembler(mapper), mapper, CostEstimator()).validateScope(claims, hits, null) to calls
        }
        val requirements = ClaimScopePromptAssembler.requirements(SourceScopeInspector.parse(raw(), claims, hits, mapper)!!, hits)
        val conditions = requirements.map { mapOf("id" to it.id, "applicable" to true, "preserved" to false, "anchor" to "", "reason" to "Условия не указаны.") }
        val rejected = check(conditions).first
        assertEquals(SupportCheckStatus.REJECTED, rejected.status)
        assertEquals(2, rejected.generations().size)
        assertEquals("invalid_scope_checks", check(emptyList()).first.issues.single().code)
        val invalid = check(emptyList(), "{\"bindings\":[]}")
        assertEquals(SupportCheckStatus.INVALID_RESPONSE, invalid.first.status)
        assertEquals(1, invalid.second)
        val error = check(conditions, failSecond = true).first
        assertEquals(SupportCheckStatus.INVALID_RESPONSE, error.status)
        assertEquals(2, error.generations().size)
        assertNotNull(error.generation.usage)
        assertNull(error.additionalGenerations.single().usage)
        assertEquals("deepseek-v4-pro", error.additionalGenerations.single().model)
    }
    @Test fun `scope contract rejects duplicates foreign ids omissions and source only anchors`() {
        val requirements = ClaimScopePromptAssembler.requirements(SourceScopeInspector.parse(raw(), claims, hits, mapper)!!, hits)
        assertEquals("example_scope", requirements.first().kind)
        assertTrue(requirements.drop(1).all { it.kind == "context_review" })
        fun verdict(id: String, anchor: String = "", preserved: Boolean = false) = mapOf("id" to id, "applicable" to true, "preserved" to preserved, "anchor" to anchor, "reason" to "Проверено")
        fun parse(items: List<Any>) = ClaimScopePromptAssembler.parse(mapper.writeValueAsString(mapOf("checks" to items)), requirements, claims, null, mapper)
        val valid = requirements.map { verdict(it.id) }
        assertNotNull(parse(valid))
        for (items in listOf(valid.drop(1), valid.drop(1) + valid.last(), valid.drop(1) + verdict("foreign"), valid.drop(1) + verdict("R0", "условие только в источнике", true))) assertNull(parse(items))
        val explicit = claims.map { it.copy(text = "В приведённом в книге примере: " + it.text) }
        val checks = mapper.writeValueAsString(mapOf("checks" to requirements.map { verdict(it.id, "В приведённом в книге примере", true) }))
        assertNotNull(ClaimScopePromptAssembler.parse(checks, requirements, explicit, null, mapper))
    }
    @Test fun `general classification with empty premises still requires reviewing every source paragraph`() {
        val requirements = ClaimScopePromptAssembler.requirements(SourceScopeInspector.parse(raw("general", emptyList()), claims, hits, mapper)!!, hits)
        assertEquals(ClaimSupportPromptAssembler.passages(hits.single().chunk.text), requirements.map { it.text })
        assertTrue(requirements.all { it.kind == "context_review" })
        val entries = requirements.map { mapOf("id" to it.id, "applicable" to false, "preserved" to true, "anchor" to "", "reason" to "Нет ограничения этого утверждения.") }
        assertNotNull(ClaimScopePromptAssembler.parse(mapper.writeValueAsString(mapOf("checks" to entries)), requirements, claims, null, mapper))
        val mandatory = requirements.map { it.copy(kind = "premise") }
        assertNull(ClaimScopePromptAssembler.parse(mapper.writeValueAsString(mapOf("checks" to entries)), mandatory, claims, null, mapper))
    }
}
