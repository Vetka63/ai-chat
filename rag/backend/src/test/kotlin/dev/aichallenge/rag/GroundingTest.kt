package dev.aichallenge.rag

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.answering.ports.LlmClient
import dev.aichallenge.rag.answering.services.*
import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.grounding.adapters.ExactCitationValidator
import dev.aichallenge.rag.grounding.enums.GroundedStatus
import dev.aichallenge.rag.grounding.models.*
import dev.aichallenge.rag.grounding.services.*
import dev.aichallenge.rag.indexing.models.*
import dev.aichallenge.rag.indexing.ports.IndexRepository
import dev.aichallenge.rag.retrieval.models.*
import dev.aichallenge.rag.retrieval.services.SearchService
import dev.aichallenge.rag.retrieval.selection.adapters.SimilarityCandidateSelector
import dev.aichallenge.rag.rewriting.models.RewriteTrace
import dev.aichallenge.rag.rewriting.ports.QueryRewriter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import tools.jackson.module.kotlin.jacksonObjectMapper

/** Fixtures доказывают gate и точные цитаты, но не заменяют ручную проверку смысла ответа. */
class GroundingTest {
    private val mapper = jacksonObjectMapper()
    private val quote = "Git сохраняет состояние файла на момент git add."
    private val hit = SearchHit(1, .8, Chunk("c1", "doc", "book.asc", "Git", "Индекс", listOf("Индекс"), 0, 100, 100 + quote.length + 3, 1, 2, "До $quote", "sha"))
    private val validator = ExactCitationValidator(mapper)
    private fun json(id: String = "c1", q: String = quote, text: String = "В коммит идёт подготовленная версия.") = mapper.writeValueAsString(mapOf("status" to "known", "claims" to listOf(mapOf("text" to text, "citations" to listOf(mapOf("chunk_id" to id, "quote" to q)))), "clarification" to null))

    @Test fun `valid quote gets server metadata and canonical offsets`() {
        val r = validator.validate(json(), "stop", listOf(hit))
        assertEquals(GroundedStatus.ANSWERED, r.status)
        val c = r.claims.single().citations.single()
        assertEquals("book.asc", c.source.source); assertEquals(3, c.startInChunk); assertEquals(103, c.canonicalStart)
        assertEquals(quote, hit.chunk.text.substring(c.startInChunk, c.endInChunkExclusive))
        assertEquals(1, r.sources.size)
    }
    @Test fun `unknown or excluded evidence cannot be cited`() {
        assertEquals("unknown_evidence_id", validator.validate(json("invented"), "stop", listOf(hit)).issues.single().code)
        assertEquals(GroundedStatus.INVALID_EVIDENCE, validator.validate(json(), "stop", emptyList()).status)
    }
    @Test fun `changed whitespace case paraphrase and ellipsis rejected`() {
        for (q in listOf(quote.lowercase(), quote.replace(" ", "  "), quote.replace("состояние", "версию"), quote.replace("состояние файла", "..."))) {
            val r = validator.validate(json(q = q), "stop", listOf(hit))
            assertEquals(GroundedStatus.INVALID_EVIDENCE, r.status); assertTrue(r.claims.isEmpty()); assertTrue(r.sources.isEmpty())
        }
    }
    @Test fun `truncated JSON invalid before parsing no partial release`() {
        assertEquals("truncated_evidence", validator.validate(json(), "length", listOf(hit)).issues.single().code)
        assertEquals("invalid_evidence_json", validator.validate("{bad", "stop", listOf(hit)).issues.single().code)
    }
    @Test fun `optional null clarification never relaxes evidence or unknown`() {
        assertEquals(GroundedStatus.ANSWERED, validator.validate(json().replace(",\"clarification\":null", ""), "stop", listOf(hit)).status)
        assertEquals(GroundedStatus.INVALID_EVIDENCE, validator.validate(json("fake").replace(",\"clarification\":null", ""), "stop", listOf(hit)).status)
        assertEquals(GroundedStatus.INVALID_EVIDENCE, validator.validate("{\"status\":\"unknown\",\"claims\":[]}", "stop", listOf(hit)).status)
        for (bad in listOf("null", "[]", "", "true", "\"text\"")) assertEquals(GroundedStatus.INVALID_EVIDENCE, validator.validate(bad, "stop", listOf(hit)).status)
    }
    @Test fun `exact quote is not a semantic proof and quote bounds enforced`() {
        assertEquals(GroundedStatus.ANSWERED, validator.validate(json(text = "На самом деле git add удаляет файлы."), "stop", listOf(hit)).status)
        assertEquals(GroundedStatus.INVALID_EVIDENCE, validator.validate(json(q = "Git"), "stop", listOf(hit)).status)
        assertEquals(GroundedStatus.INVALID_EVIDENCE, validator.validate(json(q = "x".repeat(601)), "stop", listOf(hit)).status)
    }
    @Test fun `extra answer source absent citation and empty known rejected`() {
        val bad = listOf("{\"status\":\"known\",\"claims\":[],\"clarification\":null}", "{\"status\":\"known\",\"claims\":[{\"text\":\"x\",\"citations\":[]}],\"clarification\":null}", json().dropLast(1) + ",\"answer\":\"uncited\"}", json().replace("\"quote\":", "\"source\":\"fake\",\"quote\":"))
        for (content in bad) assertEquals(GroundedStatus.INVALID_EVIDENCE, validator.validate(content, "stop", listOf(hit)).status)
    }
    @Test fun `one bad claim suppresses entire answer not only bad sentence`() {
        val valid = mapper.readTree(json()).path("claims").first()
        val invalid = mapper.readTree(json("fake")).path("claims").first()
        val content = mapper.writeValueAsString(mapOf("status" to "known", "claims" to listOf(valid, invalid), "clarification" to null))
        val r = validator.validate(content, "stop", listOf(hit))
        assertTrue(r.claims.isEmpty()); assertEquals(1, r.issues.single().claimIndex)
    }
    @Test fun `unknown requires no claims and a clarification`() {
        val good = "{\"status\":\"unknown\",\"claims\":[],\"clarification\":\"Какую операцию Git вы имеете в виду?\"}"
        assertEquals(GroundedStatus.UNKNOWN, validator.validate(good, "stop", listOf(hit)).status)
        assertEquals(GroundedStatus.INVALID_EVIDENCE, validator.validate(good.replace("[]", "[{}]"), "stop", listOf(hit)).status)
        assertEquals(GroundedStatus.INVALID_EVIDENCE, validator.validate(good.replace("Какую операцию Git вы имеете в виду?", ""), "stop", listOf(hit)).status)
    }
    private inner class Fixture(val modelContent: String = json(), val finish: String = "stop", val fail: Boolean = false) {
        val repository = mock(IndexRepository::class.java)
        val search = mock(SearchService::class.java)
        var calls = 0; var rewriteCalls = 0; var cap: Int? = 999; var messages = emptyList<LlmMessage>()
        val usage = TokenUsage(10, 5, 15, 0, 10)
        val llm = object : LlmClient {
            override fun settings() = AnswerSettings(true, "deepseek-flash", 0.0, "disabled", 16000, null, "")
            override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion {
                calls++; this@Fixture.messages = messages; cap = maxOutputTokens
                if (fail) throw LabException("llm_unavailable", "Тестовый сбой.")
                return LlmCompletion("id", "deepseek-flash", modelContent, finish, 2, usage)
            }
        }
        val rewriter = object : QueryRewriter { override fun rewrite(question: String): RewriteTrace { rewriteCalls++; return RewriteTrace("rewritten", "deepseek-flash", "stop", 1, usage, CostEstimator().estimate("deepseek-flash", usage), emptyList(), "{}") } }
        val service: GroundingService
        init {
            val index = mock(IndexInfo::class.java); `when`(index.snapshotId).thenReturn("snapshot")
            `when`(repository.index("index")).thenReturn(index)
            `when`(search.search(eq("index") ?: "index", any(SearchRequest::class.java) ?: SearchRequest("fixture"))).thenReturn(SearchResult("index", "original", 1, 2, listOf(hit)))
            service = GroundingService(repository, search, SimilarityCandidateSelector(), rewriter, PromptAssembler(mapper), GroundingPromptAssembler(mapper), llm, validator, CostEstimator())
        }
        fun request() = GroundingRequest("original", "index")
    }
    @Test fun `weak context refuses without paid generation`() {
        val f = Fixture(); val r = f.service.answer(f.request().copy(similarityThreshold = .9))
        assertEquals(GroundedStatus.UNKNOWN, r.status); assertEquals(0, f.calls); assertEquals(0, r.llmStagesAttempted)
        assertNotNull(r.clarification); assertTrue(r.sources.isEmpty()); assertNull(r.generation)
    }
    @Test fun `server answer only comes from cited claims original question null cap`() {
        val f = Fixture(); val r = f.service.answer(f.request())
        assertEquals(GroundedStatus.ANSWERED, r.status); assertEquals(r.claims.joinToString("\n\n") { it.text }, r.answer)
        assertEquals("original", mapper.readTree(f.messages[1].content).path("question").asText()); assertNull(f.cap)
        assertEquals(15L, r.totalUsage!!.totalTokens)
    }
    @Test fun `invalid evidence preserves usage but no public claims`() {
        val f = Fixture(modelContent = json("fake")); val r = f.service.answer(f.request())
        assertEquals(GroundedStatus.INVALID_EVIDENCE, r.status); assertTrue(r.claims.isEmpty()); assertTrue(r.sources.isEmpty())
        assertNotNull(r.generation); assertEquals(15L, r.totalUsage!!.totalTokens); assertEquals(1, f.calls)
    }
    @Test fun `rewrite counts once and never replaces question`() {
        val f = Fixture(); val r = f.service.answer(f.request().copy(useRewrite = true))
        assertEquals(1, f.rewriteCalls); assertEquals(30L, r.totalUsage!!.totalTokens); assertEquals(2, r.llmStagesAttempted)
        assertEquals("original", mapper.readTree(f.messages[1].content).path("question").asText())
        verify(f.search).search("index", SearchRequest("rewritten", 10))
    }
    @Test fun `provider error is not unknown or ungrounded fallback`() {
        val f = Fixture(fail = true); val r = f.service.answer(f.request())
        assertEquals(GroundedStatus.ERROR, r.status); assertNull(r.totalUsage); assertTrue(r.sources.isEmpty()); assertEquals(1, f.calls)
    }
    @Test fun `bad settings before lookup or billing`() {
        val f = Fixture()
        for (r in listOf(f.request().copy(finalTopK = 11), f.request().copy(candidateTopK = 2), f.request().copy(similarityThreshold = Double.NaN))) assertThrows(LabException::class.java) { f.service.answer(r) }
        verifyNoInteractions(f.repository, f.search); assertEquals(0, f.calls)
    }
    @Test fun `small budget is error not false lack of knowledge`() {
        val f = Fixture()
        val large = hit.copy(chunk = hit.chunk.copy(text = "x".repeat(301)))
        `when`(f.search.search(eq("index") ?: "index", any(SearchRequest::class.java) ?: SearchRequest("fixture"))).thenReturn(SearchResult("index", "original", 1, 2, listOf(large)))
        val r = f.service.answer(f.request().copy(contextMaxCharacters = 300))
        assertEquals(GroundedStatus.ERROR, r.status); assertEquals("context_budget_too_small", r.issues.single().code); assertEquals(0, f.calls)
    }
}
