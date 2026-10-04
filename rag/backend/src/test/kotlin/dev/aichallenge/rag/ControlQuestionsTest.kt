package dev.aichallenge.rag

import dev.aichallenge.rag.answering.models.ControlQuestion
import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.documents.adapters.AsciiDocDocumentLoader
import dev.aichallenge.rag.documents.services.CorpusService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.module.kotlin.jacksonObjectMapper

/** Проверяет, что опорные источники/цитаты существуют в книге и fixtures не попали в индекс. */
class ControlQuestionsTest {
    @Test fun `ten distinct questions have real source anchors outside index`() {
        val mapper = jacksonObjectMapper()
        val cases: List<ControlQuestion> = ClassPathResource("static/evaluation/day22-cases.json").inputStream.use { mapper.readValue(it, mapper.typeFactory.constructCollectionType(List::class.java, ControlQuestion::class.java)) }
        val corpus = CorpusService(RagProperties("../corpus/progit-ru", "unused", "unused", "test"), mapper, AsciiDocDocumentLoader()).snapshot()
        assertEquals(10, cases.size)
        assertEquals(10, cases.map { it.id }.toSet().size)
        cases.forEach { question ->
            val document = corpus.documents.single { it.source.endsWith("/" + question.expectedSourceSuffix) }
            assertTrue(document.text.contains(question.evidenceQuote), "Missing evidence: ${question.id}")
            assertTrue(question.expected.isNotBlank())
        }
        assertFalse(corpus.documents.any { "evaluation" in it.source })
    }
}
