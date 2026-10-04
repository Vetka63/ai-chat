package dev.aichallenge.rag

import dev.aichallenge.rag.grounding.services.EvidencePassages
import dev.aichallenge.rag.grounding.services.EvidenceCatalog
import dev.aichallenge.rag.grounding.adapters.ExactCitationValidator
import dev.aichallenge.rag.grounding.enums.GroundedStatus
import dev.aichallenge.rag.indexing.models.Chunk
import dev.aichallenge.rag.retrieval.models.SearchHit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/** Адресация evidence не доверяет модели ни цитату, ни offsets, ни поля метаданных. */
class EvidencePassagesTest {
    private val text = "Заголовок\n\nПервая строка.\nВторая строка с `разметкой`.\n\nПоследний абзац."
    private fun hit(value: String = text) = SearchHit(1, .8, Chunk("chunk", "doc", "book/file", "Книга", "Раздел", emptyList(), 0, 100, 100 + value.length, 1, 1, value, "hash"))
    private fun response(reference: String) = """{"status":"known","claims":[{"text":"Утверждение","citations":[$reference]}],"clarification":null}"""
    @Test fun `selected passage uses exact snapshot offsets and public source`() {
        val result = ExactCitationValidator(jacksonObjectMapper()).validate(response("""{"chunk_id":"chunk","span_index":1}"""), "stop", listOf(hit()))
        assertEquals(GroundedStatus.ANSWERED, result.status)
        val c = result.claims.single().citations.single()
        assertEquals(text.substring(c.startInChunk, c.endInChunkExclusive), c.quote)
        assertEquals(c.startInChunk + 100, c.canonicalStart)
        assertEquals("book/file", c.source.source)
        assertTrue(c.quote.contains("\nВторая строка"))
    }
    @Test fun `request local evidence aliases resolve uniquely across multiple chunks`() {
        val first = hit()
        val second = first.copy(chunk = first.chunk.copy(chunkId = "second", documentId = "second-doc", source = "second-source", start = 500, text = "Второй документ.\n\nЕго собственное доказательство."))
        val entries = EvidenceCatalog.entries(listOf(first, second))
        assertEquals(entries.indices.map { "E${it + 1}" }, entries.map { it.id })
        val selected = entries.last()
        val result = ExactCitationValidator(jacksonObjectMapper()).validate(response("""{"evidence_id":"${selected.id}"}"""), "stop", listOf(first, second))
        assertEquals(GroundedStatus.ANSWERED, result.status)
        val cite = result.claims.single().citations.single()
        assertEquals("second", cite.source.chunkId)
        assertEquals("second-source", cite.source.source)
        assertEquals(selected.passage.text, cite.quote)
        assertEquals(500 + selected.passage.start, cite.canonicalStart)
    }
    @Test fun `aliases are exact unambiguous and restricted to the current context`() {
        listOf("""{"evidence_id":"E0"}""", """{"evidence_id":"E999"}""", """{"evidence_id":"e1"}""", """{"evidence_id":" E1"}""", """{"evidence_id":1}""", """{"evidence_id":"E1","chunk_id":"chunk"}""", """{"evidence_id":"E1","quote":"Подмена"}""").forEach { ref ->
            val result = ExactCitationValidator(jacksonObjectMapper()).validate(response(ref), "stop", listOf(hit()))
            assertEquals(GroundedStatus.INVALID_EVIDENCE, result.status, ref)
            assertTrue(result.claims.isEmpty())
        }
    }
    @Test fun `invalid or mixed references never resolve`() {
        listOf("-1", "999", "1.0", "true", "\"1\"", "null").forEach { n ->
            assertEquals(GroundedStatus.INVALID_EVIDENCE, ExactCitationValidator(jacksonObjectMapper()).validate(response("""{"chunk_id":"chunk","span_index":$n}"""), "stop", listOf(hit())).status)
        }
        listOf("""{"chunk_id":"other","span_index":0}""", """{"chunk_id":"chunk","span_index":0,"quote":"Подмена текста"}""").forEach {
            assertEquals(GroundedStatus.INVALID_EVIDENCE, ExactCitationValidator(jacksonObjectMapper()).validate(response(it), "stop", listOf(hit())).status)
        }
    }
    @Test fun `long paragraphs unicode CRLF and repeated passages retain coordinates`() {
        val inputs = listOf(text, "Один абзац\r\n\r\nОдин абзац", " ", "😀".repeat(1700), "а" + "😀".repeat(1700), "длинный абзац ".repeat(400))
        inputs.forEach { source ->
            val spans = EvidencePassages.split(source)
            spans.forEach { assertEquals(source.substring(it.start, it.start + it.text.length), it.text); assertTrue(it.text.length <= 1200) }
            assertEquals(source.filterNot { it.isWhitespace() }, spans.joinToString("") { it.text }.filterNot { it.isWhitespace() })
        }
    }
}
