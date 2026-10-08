package dev.aichallenge.local

import com.sun.net.httpserver.HttpServer
import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.generation.*
import dev.aichallenge.local.optimization.config.OptimizationProperties
import dev.aichallenge.local.optimization.models.*
import dev.aichallenge.local.optimization.evaluation.*
import dev.aichallenge.local.optimization.runtime.*
import dev.aichallenge.local.optimization.service.ExperimentService
import dev.aichallenge.local.optimization.skills.PromptComposer
import dev.aichallenge.local.optimization.storage.ExperimentRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OptimizationTest {
    @TempDir lateinit var folder: Path
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val card = """{"service":"orders-api","environment":"production","priority":"P1","started_at":"10:15","root_cause":null,"summary":"Оформление заказов недоступно всем."}"""
    private val expected = ExpectedCard("orders-api", "production", "P1", "10:15", null)
    private fun config() = OptimizationProperties().also { it.databasePath = folder.resolve("optimization.sqlite").toString() }

    @Test fun `schema and field correctness are separate`() {
        val evaluator = CardEvaluator(mapper)
        val good = evaluator.evaluate(card, expected)
        assertTrue(good.validJson); assertTrue(good.validSchema); assertTrue(good.exactFields!!); assertEquals(5, good.matchedFields)
        val bad = evaluator.evaluate(card.replace("P1", "P2"), expected)
        assertTrue(bad.validSchema); assertFalse(bad.exactFields!!); assertEquals(4, bad.matchedFields)
        assertNull(evaluator.evaluate(card, null).matchedFields)
    }
    @Test fun `trailing data duplicate keys fences and missing fields are not silently repaired`() {
        val evaluator = CardEvaluator(mapper)
        for (text in listOf("```json\n$card\n```", "$card $card", card.replace("\"service\":", "\"service\":null,\"service\":")))
            assertFalse(evaluator.evaluate(text, expected).validJson, text)
        assertFalse(evaluator.evaluate("{}", expected).validSchema)
        assertFalse(evaluator.evaluate(card.replace("\"root_cause\":null", "\"root_cause\":42"), expected).validSchema)
    }
    @Test fun `corpus has disjoint calibration and holdout and does not leak gold into messages`() {
        val catalog = BenchmarkCatalog(mapper); val composer = PromptComposer()
        assertEquals(20, catalog.cases.size); assertEquals(20, catalog.cases.map { it.id }.distinct().size)
        assertEquals(10, catalog.cases.count { it.split == "calibration" }); assertEquals(10, catalog.cases.count { it.split == "holdout" })
        assertTrue(catalog.find("h10").input.length > 10000)
        val messages = composer.compose(catalog.find("h05").input, PromptProfile.SPECIALIZED, true)
        assertEquals(2, messages.size); assertFalse(messages[0].content.contains("export-api")); assertTrue(messages[1].content.contains("export-api"))
        assertTrue(messages[0].content.contains("billing-api")); assertEquals(64, composer.skill.sha256.length)
    }
    @Test fun `seed topK context are not falsely advertised for DeepSeek`() {
        val plain = effectiveParameters(Provider.DEEPSEEK, Parameters())
        assertNull(plain["seed"]); assertNull(plain["context_window"]); assertNull(plain["top_k"]); assertEquals(1.0, plain["top_p"])
        val thinking = effectiveParameters(Provider.DEEPSEEK, Parameters(thinking = true))
        assertNull(thinking["temperature"]); assertEquals(0.95, thinking["top_p"])
        assertEquals(4096, effectiveParameters(Provider.LOCAL, Parameters())["context_window"])
    }
    @Test fun `sampled peaks do not mean zero when telemetry is missing`() {
        val missing = AllocationMonitor { null }; missing.close(); assertNull(missing.result().peakVramBytes)
        val monitor = AllocationMonitor { ResourceMetrics(1, 100, 90, 10, 4096, "CPU/GPU") }
        monitor.close(); assertTrue(monitor.result().samples > 0); assertEquals(90L, monitor.result().peakVramBytes)
    }
    @Test fun `startup marks pending items interrupted and preserves completed answers`() {
        val repo = ExperimentRepository(config(), mapper)
        val request = ExperimentRequest(input = "q")
        val item = ExperimentItem("custom-1", null, "q", 1, "q", null, emptyList(), "hash", ItemStatus.COMPLETED,
            Generation(card, null, "stop", 10, 3, null, null, null))
        val e = Experiment(request.id.toString(), "2026-10-08", request, "model", null, null, "v1", null, ExperimentStatus.RUNNING,
            listOf(item, item.copy(id = "custom-2", status = ItemStatus.PENDING, generation = null)))
        repo.save(e)
        val loaded = ExperimentRepository(config(), mapper).find(e.id)!!
        assertEquals(ExperimentStatus.INTERRUPTED, loaded.status); assertEquals(card, loaded.items[0].generation?.answer)
        assertEquals(ItemStatus.INTERRUPTED, loaded.items[1].status)
    }

    private fun withService(gateway: ModelGateway, action: (ExperimentService, ExperimentRepository, GenerationGate) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = if (exchange.requestURI.path == "/api/tags") """{"models":[{"name":"qwen3:4b-q4_K_M","digest":"sha-model","size":100,"details":{"quantization_level":"Q4_K_M","parameter_size":"4B"}}]}"""
                else """{"models":[{"name":"qwen3:4b-q4_K_M","size":100,"size_vram":100,"context_length":4096}]}"""
            val bytes = body.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val local = object : LocalLlmRuntime {
            override fun info() = RuntimeInfo(true, "test", listOf("qwen3:4b-q4_K_M"), listOf(LocalModel("qwen3:4b-q4_K_M", 100, "4B", "Q4_K_M")))
            override fun generate(request: GenerateRequest) = throw UnsupportedOperationException()
        }
        val repo = ExperimentRepository(config(), mapper); val gate = GenerationGate()
        val service = ExperimentService(config(), local, LocalProbe(LabProperties().also { it.baseUrl = "http://127.0.0.1:${server.address.port}" }, mapper),
            gateway, BenchmarkCatalog(mapper), PromptComposer(), CardEvaluator(mapper), repo, mapper, gate)
        try { action(service, repo, gate) } finally { service.stop(); server.stop(0) }
    }
    private fun await(repo: ExperimentRepository, id: String): Experiment {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < until) { val e = repo.find(id)!!; if (e.status != ExperimentStatus.RUNNING) return e; Thread.sleep(10) }
        fail<Nothing>("Experiment timeout")
    }
    @Test fun `UUID replay generates once and conflicting UUID is rejected`() {
        val calls = AtomicInteger()
        val gateway = object : ModelGateway { override fun generate(provider: Provider, model: String, messages: List<LlmMessage>, parameters: Parameters): Generation {
            calls.incrementAndGet(); return Generation(card, null, "stop", 300, 80, 10.0, 1.0, 80.0, model)
        } }
        withService(gateway) { service, repo, _ ->
            val request = ExperimentRequest(caseIds = listOf("c01"))
            val initial = service.start(request); val done = await(repo, initial.id)
            assertEquals(ExperimentStatus.COMPLETED, done.status); assertTrue(done.items.single().evaluation!!.exactFields!!)
            assertEquals("Q4_K_M", done.identity!!.quantization); assertEquals(1, calls.get())
            assertEquals(done, service.start(request)); assertEquals(1, calls.get())
            assertThrows(OptimizationFailure::class.java) { service.start(request.copy(input = "different")) }
            assertEquals("my conclusion", repo.note(done.id, "my conclusion").note)
            assertEquals("my conclusion", ExperimentRepository(config(), mapper).find(done.id)!!.note)
        }
    }
    @Test fun `completed HTTP does not turn invalid output into a correct card`() {
        withService(object : ModelGateway { override fun generate(provider: Provider, model: String, messages: List<LlmMessage>, parameters: Parameters) = Generation("hello", null, "stop", null, null, null, null, null) }) { service, repo, _ ->
            val done = await(repo, service.start(ExperimentRequest(caseIds = listOf("c01"))).id)
            assertEquals(ItemStatus.COMPLETED, done.items.single().status); assertFalse(done.items.single().evaluation!!.validJson)
        }
    }
    @Test fun `output limit remains truncated and partial without a fake successful card`() {
        withService(object : ModelGateway { override fun generate(provider: Provider, model: String, messages: List<LlmMessage>, parameters: Parameters) = Generation("{", null, "length", 300, 64, null, null, null) }) { service, repo, _ ->
            val done = await(repo, service.start(ExperimentRequest(caseIds = listOf("c01"))).id)
            assertEquals(ExperimentStatus.PARTIAL, done.status); assertEquals(ItemStatus.TRUNCATED, done.items.single().status)
            assertFalse(done.items.single().evaluation!!.validJson); assertEquals("{", done.items.single().generation?.answer)
        }
    }
    @Test fun `background task owns common GPU slot and releases it after failure`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        withService(object : ModelGateway { override fun generate(provider: Provider, model: String, messages: List<LlmMessage>, parameters: Parameters): Generation {
            entered.countDown(); release.await(3, TimeUnit.SECONDS); throw RuntimeFailure("context overflow")
        } }) { service, repo, gate ->
            val initial = service.start(ExperimentRequest(caseIds = listOf("c01")))
            assertTrue(entered.await(3, TimeUnit.SECONDS)); assertThrows(LabBusy::class.java) { gate.exclusive { } }
            assertThrows(LabBusy::class.java) { service.start(ExperimentRequest(caseIds = listOf("c02"))) }
            release.countDown(); val done = await(repo, initial.id)
            assertEquals(ExperimentStatus.FAILED, done.status); assertEquals("context overflow", done.items.single().error)
            // Запись terminal может появиться непосредственно перед finally lease.close.
            Thread.sleep(20); gate.exclusive { assertTrue(true) }
        }
    }
    @Test fun `invalid mixed inputs duplicates and disabled cloud never generate`() {
        withService(object : ModelGateway { override fun generate(provider: Provider, model: String, messages: List<LlmMessage>, parameters: Parameters): Generation { fail<Nothing>("Must not generate") } }) { service, _, _ ->
            for (request in listOf(ExperimentRequest(), ExperimentRequest(input = "q", caseIds = listOf("c01")),
                ExperimentRequest(caseIds = listOf("c01", "c01")), ExperimentRequest(caseIds = listOf("unknown")),
                ExperimentRequest(provider = Provider.DEEPSEEK, input = "q"))) assertThrows(OptimizationFailure::class.java) { service.start(request) }
        }
    }
}
