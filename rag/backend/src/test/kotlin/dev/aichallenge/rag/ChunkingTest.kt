package dev.aichallenge.rag

import dev.aichallenge.rag.documents.adapters.AsciiDocDocumentLoader
import dev.aichallenge.rag.documents.models.*
import dev.aichallenge.rag.indexing.enums.ChunkStrategy
import dev.aichallenge.rag.indexing.models.ChunkConfig
import dev.aichallenge.rag.indexing.services.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.BitSet

class ChunkingTest {
    private val service = ChunkingService(listOf(FixedSizeChunking(), StructuralChunking()))
    private val loader = AsciiDocDocumentLoader()
    private fun snapshot(raw: String): CorpusSnapshot {
        val doc = loader.load("book/02-git-basics/sections/test.asc", raw, "02", "https://example.test")
        val manifest = CorpusManifest("test", "test", "a".repeat(40), "b".repeat(64), "test", "test", listOf("02"), emptyList())
        return CorpusSnapshot(manifest, listOf(doc), "test")
    }
    @Test fun `both strategies cover the whole text with exact stable ranges`() {
        val s = snapshot("Перед заголовком\n\n=== Основы\n\n" + "Первая часть текста. ".repeat(70) + "\n\n==== Ветки\n\n" + "Другая часть текста. ".repeat(55))
        ChunkStrategy.entries.forEach { strategy ->
            val config = ChunkConfig(strategy, 500, 60)
            val preview = service.preview(s, config)
            assertEquals(100.0, preview.metrics.coveragePercent)
            assertEquals(preview, service.preview(s, config))
            assertEquals(preview.chunks.size, preview.chunks.map { it.chunkId }.distinct().size)
            val doc = s.documents.single()
            val bits = BitSet(doc.text.length)
            preview.chunks.forEach { c ->
                assertEquals(doc.text.substring(c.start, c.endExclusive), c.text)
                assertTrue(c.text.length <= 500)
                bits.set(c.start, c.endExclusive)
                if (strategy == ChunkStrategy.STRUCTURAL) assertEquals(1, c.sections.size)
            }
            assertEquals(doc.text.length, bits.cardinality())
        }
    }
    @Test fun `code stays data and markers do not erase commands`() {
        val s = snapshot("[[anchor]]\n=== Команды\n\n[source,console]\n----\n\$ git status\n// это команда внутри блока\n[пример]\n----\n\nАбзац после кода.")
        val doc = s.documents.single()
        assertTrue(doc.text.contains("\$ git status"))
        assertTrue(doc.text.contains("// это команда внутри блока"))
        assertFalse(doc.text.contains("[source,console]"))
        assertEquals(1, doc.blocks.count { it.kind == "code" })
        assertEquals(100.0, service.preview(s, ChunkConfig()).metrics.coveragePercent)
    }
    @Test fun `invalid overlap is rejected before processing`() {
        assertThrows(Exception::class.java) { service.validate(ChunkConfig(maxCharacters = 300, overlapCharacters = 299)) }
        assertThrows(Exception::class.java) { service.validate(ChunkConfig(maxCharacters = 0, overlapCharacters = 0)) }
    }
    @Test fun `include directives fail rather than silently discard source`() {
        assertThrows(IllegalArgumentException::class.java) { loader.load("a.asc", "include::other.asc[]", "02", "test") }
    }
    @Test fun `huge sections and code blocks are split with complete coverage`() {
        val s = snapshot("=== Длинный блок\n\n----\n" + "команда ".repeat(1000) + "\n----")
        val p = service.preview(s, ChunkConfig(ChunkStrategy.STRUCTURAL, 500, 20))
        assertEquals(100.0, p.metrics.coveragePercent)
        assertEquals(1, p.metrics.splitCodeBlocks)
        assertTrue(p.chunks.all { it.text.length <= 500 })
    }
}
