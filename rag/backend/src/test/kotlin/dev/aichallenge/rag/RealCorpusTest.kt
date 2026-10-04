package dev.aichallenge.rag

import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.documents.adapters.AsciiDocDocumentLoader
import dev.aichallenge.rag.documents.services.CorpusService
import dev.aichallenge.rag.indexing.enums.ChunkStrategy
import dev.aichallenge.rag.indexing.models.ChunkConfig
import dev.aichallenge.rag.indexing.services.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/** Интеграционная проверка именно закреплённой книги, включая оба include, а не только маленькой фикстуры. */
class RealCorpusTest {
    @Test fun `pinned corpus parses includes meets volume and both chunkers cover all text`() {
        val corpus = CorpusService(RagProperties("../corpus/progit-ru", "unused", "unused", "test"), jacksonObjectMapper(), AsciiDocDocumentLoader())
        val snapshot = corpus.snapshot()
        assertEquals(36, snapshot.documents.size)
        assertTrue(corpus.info().estimatedPages >= 30)
        assertTrue(snapshot.documents.none { "include::" in it.text })
        assertTrue(snapshot.documents.first { it.source.endsWith("credentials.asc") }.text.contains("#!/usr/bin/env ruby"))
        val chunking = ChunkingService(listOf(FixedSizeChunking(), StructuralChunking()))
        ChunkStrategy.entries.forEach { strategy ->
            val preview = chunking.preview(snapshot, ChunkConfig(strategy))
            assertEquals(100.0, preview.metrics.coveragePercent)
            assertTrue(preview.chunks.all { it.text.length <= 3000 })
        }
        val edge = chunking.preview(snapshot, ChunkConfig(ChunkStrategy.STRUCTURAL, 300, 149))
        assertEquals(100.0, edge.metrics.coveragePercent)
        assertTrue(edge.metrics.indexedCharacters <= 2L * edge.metrics.uniqueCharacters)
        assertTrue(edge.chunks.groupBy { it.documentId to it.endExclusive }.values.all { it.size <= 2 })
        println("Structural 300/149: ${edge.metrics.chunkCount} chunks, ${edge.metrics.indexedCharacters} characters, coverage ${edge.metrics.coveragePercent}")
    }
}
