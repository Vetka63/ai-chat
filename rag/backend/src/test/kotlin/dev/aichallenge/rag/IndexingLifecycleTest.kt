package dev.aichallenge.rag

import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.documents.adapters.AsciiDocDocumentLoader
import dev.aichallenge.rag.documents.models.*
import dev.aichallenge.rag.documents.services.CorpusService
import dev.aichallenge.rag.embeddings.ports.*
import dev.aichallenge.rag.indexing.adapters.SqliteIndexRepository
import dev.aichallenge.rag.indexing.enums.JobStatus
import dev.aichallenge.rag.indexing.models.*
import dev.aichallenge.rag.indexing.services.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Проверяет границы job на настоящем SQLite, но без внешних embeddings и LLM. */
class IndexingLifecycleTest {
    @TempDir lateinit var temp: Path
    private fun run(provider: EmbeddingProvider, action: (IndexingService, SqliteIndexRepository) -> Unit) {
        val doc = AsciiDocDocumentLoader().load("book/test.asc", "=== Тест\n\n" + "данные ".repeat(300), "test", "test")
        val snapshot = CorpusSnapshot(CorpusManifest("test", "test", "a".repeat(40), "b".repeat(64), "test", "test", emptyList(), emptyList()), listOf(doc), "snapshot")
        val corpus = mock(CorpusService::class.java)
        `when`(corpus.snapshot()).thenReturn(snapshot)
        val p = RagProperties("unused", temp.resolve("lab.sqlite").toString(), "unused", "test", batchSize = 1)
        val repository = SqliteIndexRepository(p, jacksonObjectMapper())
        val service = IndexingService(corpus, ChunkingService(listOf(FixedSizeChunking(), StructuralChunking())), provider, repository, p)
        try { action(service, repository) } finally { service.shutdown() }
    }
    private fun finished(repository: SqliteIndexRepository, id: String): IndexJob {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val job = repository.job(id)
            if (job.status in setOf(JobStatus.FAILED, JobStatus.READY)) return job
            Thread.sleep(10)
        }
        throw AssertionError("Index job did not finish")
    }
    @Test fun `later batch failure never publishes partial vectors`() {
        val provider = object : EmbeddingProvider {
            var batches = 0
            override fun identity() = EmbeddingIdentity("test", "digest", 2)
            override fun embed(texts: List<String>): EmbeddingBatch {
                if (++batches == 2) throw LabException("batch_failed", "test")
                return EmbeddingBatch(texts.map { floatArrayOf(1f, 0f) }, 1, 1)
            }
        }
        run(provider) { service, repository ->
            val job = service.start(ChunkConfig(maxCharacters = 300, overlapCharacters = 0))
            assertEquals(JobStatus.FAILED, finished(repository, job.id).status)
            assertTrue(repository.indexes().isEmpty())
            assertNull(repository.job(job.id).indexId)
        }
    }
    @Test fun `concurrent start rejected and changed model cannot publish`() {
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        val provider = object : EmbeddingProvider {
            var identityReads = 0
            override fun identity() = EmbeddingIdentity("test", if (++identityReads == 1) "a" else "b", 2)
            override fun embed(texts: List<String>): EmbeddingBatch {
                entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS))
                return EmbeddingBatch(texts.map { floatArrayOf(1f, 0f) }, 1, 1)
            }
        }
        run(provider) { service, repository ->
            val config = ChunkConfig(maxCharacters = 300, overlapCharacters = 0)
            val job = service.start(config)
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals("indexing_busy", assertThrows(LabException::class.java) { service.start(config) }.code)
            } finally { proceed.countDown() }
            assertEquals(JobStatus.FAILED, finished(repository, job.id).status)
            assertTrue(repository.indexes().isEmpty())
        }
    }
}
