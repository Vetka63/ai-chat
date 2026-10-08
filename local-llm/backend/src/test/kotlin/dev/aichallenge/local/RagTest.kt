package dev.aichallenge.local

import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.generation.*
import dev.aichallenge.local.rag.config.RagProperties
import dev.aichallenge.local.rag.corpus.CorpusService
import dev.aichallenge.local.rag.embeddings.*
import dev.aichallenge.local.rag.experiments.ExperimentService
import dev.aichallenge.local.rag.generation.*
import dev.aichallenge.local.rag.indexing.*
import dev.aichallenge.local.rag.models.*
import dev.aichallenge.local.rag.retrieval.RetrievalService
import dev.aichallenge.local.rag.storage.RagRepository
import dev.aichallenge.local.rag.web.reportText
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RagTest {
    @TempDir lateinit var folder: Path
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private fun config() = RagProperties().also { it.databasePath = folder.resolve("rag.sqlite").toString(); it.cloudEnabled = true; it.deepseekApiKey = "test-private" }
    private class FakeEmbedding(var hash: String = "digest") : EmbeddingEngine {
        val calls = AtomicInteger()
        override fun digest() = hash
        override fun embed(texts: List<String>) = EmbeddingBatch(texts.map { listOf(1.0, 0.0) }, 7).also { calls.incrementAndGet() }
    }
    private fun repository() = RagRepository(config(), mapper)
    private fun ready(repo: RagRepository): RagIndex {
        val doc = RagDocument("d", "Тест", "test.txt", "hash", "Воин носил восемь имён на броне. ".repeat(20), Instant.now().toString())
        repo.saveDocument(doc)
        val chunks = ParagraphChunker().chunk(doc, 1400, 180)
        val index = RagIndex("i", Instant.now().toString(), IndexRequest(), listOf(doc.id), IndexStatus.READY, chunks.size, chunks.size, EmbeddingSpace(config().embeddingModel, "digest", 2))
        repo.saveIndex(index); repo.publish(index, chunks.map { VectorChunk(it, listOf(1.0, 0.0)) })
        return index
    }
    private fun req(mode: RagMode = RagMode.LOCAL) = RagRequest(UUID.randomUUID().toString(), "i", "Сколько имён?", mode)
    private fun generator(name: String, action: (List<LlmMessage>) -> String = { "Восемь имён [S1]." }) = object : RagGenerator {
        override val provider = name
        override fun generate(request: RagRequest, messages: List<LlmMessage>) = ModelAnswer(name, "test", "test", RunStatus.COMPLETED, answer = action(messages), inputTokens = 20, outputTokens = 10, finishReason = "stop")
    }
    private fun await(repo: RagRepository, id: String): RagExperiment {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < end) { val run = repo.experiment(id); if (run.status !in setOf(ExperimentStatus.PREPARING, ExperimentStatus.RUNNING)) return run; Thread.sleep(15) }
        fail<Unit>("Experiment timed out"); error("unreachable")
    }
    @Test fun `UTF8 import strips explicit advert marker and deduplicates`() {
        val corpus = CorpusService(repository())
        val raw = "РЕКЛАМА ДРУГОЙ КНИГИ\n\nКРАКЕН\n\n" + "Основной рассказ. ".repeat(20)
        val first = corpus.import(raw.toByteArray(), "../book.txt", "Кракен", "КРАКЕН")
        assertEquals(first.id, corpus.import(raw.toByteArray(), "../book.txt", "Кракен", "КРАКЕН").id)
        assertEquals(1, corpus.documents().size)
        assertFalse(repository().document(first.id).text.contains("РЕКЛАМА"))
        assertThrows(RagFailure::class.java) { corpus.import(byteArrayOf(0xff.toByte()), "bad.txt", "Bad", null) }
        assertThrows(RagFailure::class.java) { corpus.import(raw.toByteArray(), "a.txt", "A", "НЕСУЩЕСТВУЮЩИЙ") }
    }
    @Test fun `chunks preserve exact source coverage offsets unicode and sections`() {
        val text = "ПРОЛОГ\n\n" + "Первый абзац с эмодзи 🐺. ".repeat(40) + "\n\nГЛАВА 1\n\n" + "Следующая история. ".repeat(80)
        val doc = RagDocument("d", "Book", "a.txt", "h", text, "now")
        val chunks = ParagraphChunker().chunk(doc, 800, 100)
        assertTrue(chunks.size > 3); assertEquals(0, chunks.first().start); assertEquals(text.length, chunks.last().endExclusive)
        chunks.forEach { assertEquals(text.substring(it.start, it.endExclusive), it.text); assertTrue(it.text.length <= 800); assertFalse(Character.isLowSurrogate(it.text.first())) }
        chunks.zipWithNext().forEach { (a, b) -> assertTrue(b.start <= a.endExclusive); assertTrue(b.start > a.start) }
        assertTrue(chunks.any { it.section == "ГЛАВА 1" })
    }
    @Test fun `local run calls no cloud and duplicate UUID never regenerates`() {
        val repo = repository(); ready(repo); val embedding = FakeEmbedding(); val localCalls = AtomicInteger(); val cloudCalls = AtomicInteger()
        val service = ExperimentService(repo, RetrievalService(repo, embedding, config(), mapper), listOf(generator("LOCAL") { localCalls.incrementAndGet(); "Восемь [S1]" }, generator("DEEPSEEK") { cloudCalls.incrementAndGet(); "bad" }), config(), LabProperties(), GenerationGate())
        try {
            val request = req(); service.start(request); val result = await(repo, request.requestId)
            assertEquals(ExperimentStatus.COMPLETED, result.status); assertEquals(1, localCalls.get()); assertEquals(0, cloudCalls.get()); assertEquals(1, embedding.calls.get())
            assertEquals(result, service.start(request)); assertEquals(1, localCalls.get())
            assertThrows(RagFailure::class.java) { service.start(request.copy(question = "other")) }
            assertEquals(CitationStatus.VALID, result.answers.single().citationStatus)
            assertEquals(result, repository().experiment(result.id))
            assertTrue(reportText(result).contains(result.retrieval!!.promptSha256)); assertFalse(reportText(result).contains("test-private"))
        } finally { service.close() }
    }
    @Test fun `compare is actually concurrent fast cloud visible while local waits same messages`() {
        val repo = repository(); ready(repo); val embedding = FakeEmbedding(); val started = CountDownLatch(2); val release = CountDownLatch(1)
        val received = java.util.concurrent.ConcurrentHashMap<String, List<LlmMessage>>()
        val service = ExperimentService(repo, RetrievalService(repo, embedding, config(), mapper), listOf(
            generator("LOCAL") { received["LOCAL"] = it; started.countDown(); release.await(3, TimeUnit.SECONDS); "Восемь [S1]" },
            generator("DEEPSEEK") { received["DEEPSEEK"] = it; started.countDown(); "Восемь [S1]" }), config(), LabProperties(), GenerationGate())
        try {
            val request = req(RagMode.COMPARE); service.start(request)
            assertTrue(started.await(2, TimeUnit.SECONDS), "Providers were not started concurrently")
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (repo.experiment(request.requestId).answers.first { it.provider == "DEEPSEEK" }.status == RunStatus.RUNNING && System.nanoTime() < end) Thread.sleep(10)
            val partial = repo.experiment(request.requestId)
            assertEquals(RunStatus.COMPLETED, partial.answers.first { it.provider == "DEEPSEEK" }.status)
            assertEquals(RunStatus.RUNNING, partial.answers.first { it.provider == "LOCAL" }.status)
            assertEquals(received["LOCAL"], received["DEEPSEEK"]); assertEquals(1, embedding.calls.get())
            release.countDown(); assertEquals(ExperimentStatus.COMPLETED, await(repo, request.requestId).status)
        } finally { release.countDown(); service.close() }
    }
    @Test fun `one provider failure preserves other answer without fallback`() {
        val repo = repository(); ready(repo)
        val service = ExperimentService(repo, RetrievalService(repo, FakeEmbedding(), config(), mapper), listOf(generator("LOCAL") { throw RagFailure("offline") }, generator("DEEPSEEK")), config(), LabProperties(), GenerationGate())
        try { val request = req(RagMode.COMPARE); service.start(request); val result = await(repo, request.requestId)
            assertEquals(ExperimentStatus.PARTIAL, result.status); assertEquals(RunStatus.FAILED, result.answers.first().status); assertEquals("Восемь имён [S1].", result.answers.last().answer)
        } finally { service.close() }
    }
    @Test fun `changed embedding identity blocks generation`() {
        val repo = repository(); ready(repo); val count = AtomicInteger()
        val service = ExperimentService(repo, RetrievalService(repo, FakeEmbedding("different"), config(), mapper), listOf(generator("LOCAL") { count.incrementAndGet(); "bad" }), config(), LabProperties(), GenerationGate())
        try { val request = req(); service.start(request); assertEquals(ExperimentStatus.FAILED, await(repo, request.requestId).status); assertEquals(0, count.get()) } finally { service.close() }
    }
    @Test fun `scope cannot escape snapshot and invalid references are visibly marked`() {
        val repo = repository(); ready(repo)
        val retrieval = RetrievalService(repo, FakeEmbedding(), config(), mapper)
        assertThrows(RagFailure::class.java) { retrieval.prepare(req().copy(documentIds = listOf("other"))) }
        val service = ExperimentService(repo, retrieval, listOf(generator("LOCAL") { "Ответ [S99]" }), config(), LabProperties(), GenerationGate())
        try { val request = req(); service.start(request); assertEquals(CitationStatus.INVALID, await(repo, request.requestId).answers.single().citationStatus) } finally { service.close() }
    }
    @Test fun `restart interrupts pending records never reruns providers`() {
        val repo = repository(); val request = req()
        repo.saveExperiment(RagExperiment(request.requestId, "now", request, ExperimentStatus.RUNNING, answers = listOf(ModelAnswer("LOCAL", "qwen3:8b"))))
        val restored = repository().experiment(request.requestId)
        assertEquals(ExperimentStatus.INTERRUPTED, restored.status); assertEquals(RunStatus.FAILED, restored.answers.single().status)
    }
    @Test fun `cosine rejects invalid vectors and respects similarity`() {
        assertEquals(1.0, cosine(listOf(1.0, 0.0), listOf(1.0, 0.0)), 0.0001)
        assertEquals(0.0, cosine(listOf(1.0, 0.0), listOf(0.0, 1.0)), 0.0001)
        assertThrows(RagFailure::class.java) { cosine(listOf(0.0), listOf(1.0)) }
        assertThrows(RagFailure::class.java) { cosine(listOf(Double.NaN), listOf(1.0)) }
    }
    @Test fun `hybrid retains rare detail even when dense ranks it lower`() {
        val corpus = (1..20).map { i -> Chunk("$i", "d", "a.txt", "Кракен", "Начало", i, 0, 40, if (i == 20) "Восемь имён вырезаны на броне." else "Квара сражался с врагами и носил оружие.") to (1.0 - i * 0.01) }
        val ranked = dev.aichallenge.local.rag.retrieval.HybridRanking().rank("Сколько имён на броне Квары?", corpus)
        assertTrue(ranked.take(5).any { it.chunk.id == "20" })
        assertEquals(20, ranked.first { it.chunk.id == "20" }.denseRank)
        assertEquals(0.8, ranked.first { it.chunk.id == "20" }.similarity, 0.00001)
    }
    @Test fun `below threshold produces unknown without any model call`() {
        val repo = repository(); ready(repo); val calls = AtomicInteger()
        val embeddings = object : EmbeddingEngine {
            override fun digest() = "digest"
            override fun embed(texts: List<String>) = EmbeddingBatch(texts.map { listOf(0.0, 1.0) }, 7)
        }
        val service = ExperimentService(repo, RetrievalService(repo, embeddings, config(), mapper), listOf(generator("LOCAL") { calls.incrementAndGet(); "bad" }), config(), LabProperties(), GenerationGate())
        try { val request = req(); service.start(request); val result = await(repo, request.requestId)
            assertEquals(ExperimentStatus.UNKNOWN, result.status); assertTrue(result.answers.isEmpty()); assertEquals(0, calls.get())
        } finally { service.close() }
    }
    @Test fun `query embedding cache preserves prompt but never caches generated answers`() {
        val repo = repository(); ready(repo); val embedding = FakeEmbedding(); val retrieval = RetrievalService(repo, embedding, config(), mapper)
        val first = retrieval.prepare(req()); val second = retrieval.prepare(req())
        assertFalse(first.snapshot.embeddingCacheHit); assertTrue(second.snapshot.embeddingCacheHit)
        assertEquals(first.messages, second.messages); assertEquals(first.snapshot.promptSha256, second.snapshot.promptSha256)
        assertEquals(1, embedding.calls.get()); assertEquals(0L, second.snapshot.embeddingTokens)
        embedding.hash = "different"; assertThrows(RagFailure::class.java) { retrieval.prepare(req()) }
    }
    @Test fun `explicit story title adds opening with transparent metadata origin`() {
        val repo = repository(); ready(repo)
        val retrieval = RetrievalService(repo, FakeEmbedding(), config(), mapper)
        val result = retrieval.prepare(req().copy(question = "Что сказано в рассказе Тест?"))
        assertEquals("BOOK_OPENING", result.snapshot.hits.first().origin)
        assertEquals(0, result.snapshot.hits.first().chunk.ordinal)
        val ranking = dev.aichallenge.local.rag.retrieval.HybridRanking()
        assertTrue(ranking.mentionsTitle("В Молниеносном рейсе кто пилот?", "Молниеносный рейс"))
        assertTrue(ranking.mentionsTitle("В Сумрачных часах какой город?", "Сумрачные часы"))
        assertFalse(ranking.mentionsTitle("В Кракене кто герой?", "Сумрачные часы"))
    }
}
