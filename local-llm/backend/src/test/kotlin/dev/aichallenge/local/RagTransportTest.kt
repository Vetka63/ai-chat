package dev.aichallenge.local

import com.sun.net.httpserver.HttpServer
import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.generation.*
import dev.aichallenge.local.rag.config.RagProperties
import dev.aichallenge.local.rag.embeddings.OllamaEmbeddings
import dev.aichallenge.local.rag.generation.DeepSeekRagGenerator
import dev.aichallenge.local.rag.models.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class RagTransportTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    @Test fun `DeepSeek receives chosen params and preserves reported usage without leaking key`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger()
        server.createContext("/chat/completions") { exchange ->
            calls.incrementAndGet(); assertEquals("Bearer private-test-key", exchange.requestHeaders.getFirst("Authorization"))
            val payload = mapper.readTree(exchange.requestBody.bufferedReader().readText())
            assertEquals("deepseek-flash", payload.path("model").asText()); assertEquals(0.4, payload.path("temperature").asDouble())
            assertFalse(payload.has("max_tokens")); assertEquals("disabled", payload.path("thinking").path("type").asText())
            assertFalse(payload.toString().contains("private-test-key"))
            val json = """{"model":"deepseek-flash","choices":[{"message":{"content":"Ответ [S1]"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""".toByteArray()
            exchange.sendResponseHeaders(200, json.size.toLong()); exchange.responseBody.use { it.write(json) }
        }
        server.start()
        try {
            val config = RagProperties().also { it.cloudEnabled = true; it.deepseekApiKey = "private-test-key"; it.deepseekBaseUrl = "http://127.0.0.1:${server.address.port}" }
            val answer = DeepSeekRagGenerator(config, mapper).generate(RagRequest(UUID.randomUUID().toString(), "i", "Вопрос", RagMode.DEEPSEEK, temperature = 0.4), listOf(LlmMessage(LlmRole.USER, "Данные")))
            assertEquals(RunStatus.COMPLETED, answer.status); assertEquals(10L, answer.inputTokens); assertEquals(5L, answer.outputTokens); assertEquals(1, calls.get())
            config.cloudEnabled = false
            assertThrows(RagFailure::class.java) { DeepSeekRagGenerator(config, mapper).generate(RagRequest("id", "i", "Q"), emptyList()) }
            assertEquals(1, calls.get())
        } finally { server.stop(0) }
    }
    @Test fun `provider errors never expose raw body credentials and are not retried`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0); val calls = AtomicInteger()
        server.createContext("/chat/completions") { exchange ->
            calls.incrementAndGet(); val raw = "sensitive-provider-body".toByteArray(); exchange.sendResponseHeaders(500, raw.size.toLong()); exchange.responseBody.use { it.write(raw) }
        }; server.start()
        try {
            val config = RagProperties().also { it.cloudEnabled = true; it.deepseekApiKey = "key"; it.deepseekBaseUrl = "http://127.0.0.1:${server.address.port}" }
            val failure = assertThrows(RagFailure::class.java) { DeepSeekRagGenerator(config, mapper).generate(RagRequest("id", "i", "Q"), emptyList()) }
            assertFalse(failure.message!!.contains("sensitive")); assertEquals(1, calls.get())
        } finally { server.stop(0) }
    }
    @Test fun `embedding adapter forbids cloud model and silent truncation`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var remote = false
        server.createContext("/api/tags") { exchange ->
            val extra = if (remote) ",\"remote_host\":\"example.invalid\"" else ""
            val json = """{"models":[{"name":"qwen3-embedding:0.6b","digest":"digest"$extra}]}""".toByteArray()
            exchange.sendResponseHeaders(200, json.size.toLong()); exchange.responseBody.use { it.write(json) }
        }
        server.createContext("/api/embed") { exchange ->
            val request = mapper.readTree(exchange.requestBody.bufferedReader().readText()); assertFalse(request.path("truncate").asBoolean())
            val json = """{"model":"qwen3-embedding:0.6b","embeddings":[[1,0]],"prompt_eval_count":7}""".toByteArray()
            exchange.sendResponseHeaders(200, json.size.toLong()); exchange.responseBody.use { it.write(json) }
        }; server.start()
        try {
            val embedding = OllamaEmbeddings(LabProperties().also { it.baseUrl = "http://127.0.0.1:${server.address.port}" }, RagProperties(), mapper)
            assertEquals("digest", embedding.digest()); assertEquals(listOf(1.0, 0.0), embedding.embed(listOf("Text")).vectors.single())
            remote = true; assertThrows(RagFailure::class.java) { embedding.digest() }
        } finally { server.stop(0) }
    }
}
