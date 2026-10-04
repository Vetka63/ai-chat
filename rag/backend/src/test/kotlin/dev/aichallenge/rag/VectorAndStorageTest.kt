package dev.aichallenge.rag

import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.documents.adapters.AsciiDocDocumentLoader
import dev.aichallenge.rag.documents.models.*
import dev.aichallenge.rag.indexing.adapters.SqliteIndexRepository
import dev.aichallenge.rag.indexing.enums.JobStatus
import dev.aichallenge.rag.indexing.models.*
import dev.aichallenge.rag.indexing.services.*
import dev.aichallenge.rag.retrieval.services.VectorMath
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path

class VectorAndStorageTest {
    @TempDir lateinit var temp: Path
    @Test fun `cosine handles parallel orthogonal and opposite vectors`() {
        assertEquals(1.0, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(2f, 0f)), 1e-8)
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-8)
        assertEquals(-1.0, VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-8)
        assertThrows(IllegalArgumentException::class.java) { VectorMath.validate(floatArrayOf(Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { VectorMath.validate(floatArrayOf(0f)) }
        assertThrows(IllegalArgumentException::class.java) { VectorMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 1f)) }
    }
    @Test fun `snapshot index vectors and job survive repository restart`() {
        val p = RagProperties("unused", temp.resolve("lab.sqlite").toString(), "http://unused", "test")
        val mapper = jacksonObjectMapper()
        val repo = SqliteIndexRepository(p, mapper)
        val doc = AsciiDocDocumentLoader().load("book/02-git-basics/sections/test.asc", "=== Git\n\nФрагмент книги.", "02", "https://example.test")
        val manifest = CorpusManifest("test", "test", "a".repeat(40), "b".repeat(64), "test", "test", emptyList(), emptyList())
        val snapshot = CorpusSnapshot(manifest, listOf(doc), "snapshot")
        val preview = ChunkingService(listOf(FixedSizeChunking(), StructuralChunking())).preview(snapshot, ChunkConfig())
        val job = IndexJob("job", ChunkConfig(), JobStatus.RUNNING, 0, 1, "now", "now")
        repo.saveJob(job)
        val index = IndexInfo("index", snapshot.snapshotId, "now", job.config, EmbeddingIdentity("test", "digest", 2), preview.metrics, 10, 8, 15, 8)
        repo.publish(snapshot, index, preview.chunks, listOf(floatArrayOf(.5f, -.5f)), job.copy(status = JobStatus.READY, processed = 1, indexId = "index"))
        val restarted = SqliteIndexRepository(p, mapper)
        assertEquals(index, restarted.index("index"))
        assertEquals(doc, restarted.document("index", doc.id))
        assertArrayEquals(floatArrayOf(.5f, -.5f), restarted.vectors("index").single().second)
        assertEquals(JobStatus.READY, restarted.job("job").status)
    }
    @Test fun `interrupted running job becomes failed on restart`() {
        val p = RagProperties("unused", temp.resolve("interrupted.sqlite").toString(), "unused", "test")
        val mapper = jacksonObjectMapper()
        val repo = SqliteIndexRepository(p, mapper)
        repo.saveJob(IndexJob("job", ChunkConfig(), JobStatus.RUNNING, 1, 20, "now", "now"))
        val restarted = SqliteIndexRepository(p, mapper)
        assertEquals(JobStatus.FAILED, restarted.job("job").status)
        assertTrue(restarted.indexes().isEmpty())
    }
    @Test fun `failed publish rolls back snapshot index chunks and ready status`() {
        val p = RagProperties("unused", temp.resolve("rollback.sqlite").toString(), "unused", "test")
        val repo = SqliteIndexRepository(p, jacksonObjectMapper())
        val doc = AsciiDocDocumentLoader().load("book/test.asc", "=== Git\n\nТекст книги.", "02", "https://example.test")
        val snapshot = CorpusSnapshot(CorpusManifest("test", "test", "a".repeat(40), "b".repeat(64), "test", "test", emptyList(), emptyList()), listOf(doc), "snapshot")
        val preview = ChunkingService(listOf(FixedSizeChunking(), StructuralChunking())).preview(snapshot, ChunkConfig())
        val job = IndexJob("job", ChunkConfig(), JobStatus.RUNNING, 0, 1, "now", "now")
        repo.saveJob(job)
        val index = IndexInfo("broken", snapshot.snapshotId, "now", job.config, EmbeddingIdentity("test", "digest", 2), preview.metrics, 1, 1, null, 8)
        assertThrows(IllegalArgumentException::class.java) { repo.publish(snapshot, index, preview.chunks, listOf(floatArrayOf(1f)), job.copy(status = JobStatus.READY)) }
        assertTrue(repo.indexes().isEmpty())
        assertEquals(JobStatus.RUNNING, repo.job("job").status)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${p.databasePath}").use { connection ->
            listOf("snapshots", "indexes", "chunks").forEach { table ->
                connection.createStatement().use { statement -> statement.executeQuery("SELECT count(*) FROM $table").use { result -> result.next(); assertEquals(0, result.getInt(1)) } }
            }
        }
    }
}
