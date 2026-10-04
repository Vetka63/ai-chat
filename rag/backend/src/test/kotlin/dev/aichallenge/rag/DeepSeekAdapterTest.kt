package dev.aichallenge.rag

import com.sun.net.httpserver.HttpServer
import dev.aichallenge.rag.answering.adapters.DeepSeekLlmClient
import dev.aichallenge.rag.answering.models.LlmMessage
import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.config.DeepSeekProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress

/** Локальный HTTP fixture проверяет протокол и защиту ключа без обращения к DeepSeek. */
class DeepSeekAdapterTest {
    private val valid = """{"id":"request","model":"deepseek-flash","choices":[{"finish_reason":"stop","message":{"content":"Ответ"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"prompt_cache_hit_tokens":4,"prompt_cache_miss_tokens":6}}"""
    private fun withServer(body: String, status: Int = 200, action: (DeepSeekLlmClient, MutableList<String>) -> Unit) {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            requests.add(exchange.requestBody.bufferedReader().readText())
            assertEquals("Bearer test-only", exchange.requestHeaders.getFirst("Authorization"))
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { action(DeepSeekLlmClient(DeepSeekProperties("test-only", "http://127.0.0.1:${server.address.port}"), jacksonObjectMapper()), requests) }
        finally { server.stop(0) }
    }
    @Test fun `optional max tokens omitted and thinking disabled`() {
        withServer(valid) { client, requests ->
            val result = client.complete(listOf(LlmMessage("user", "Вопрос")), null)
            val request = jacksonObjectMapper().readTree(requests.single())
            assertFalse(request.has("max_tokens"))
            assertEquals("disabled", request.path("thinking").path("type").asText())
            assertEquals(0.0, request.path("temperature").asDouble())
            assertEquals(15L, result.usage!!.totalTokens)
            assertEquals(4L, result.usage!!.cacheHitTokens)
            assertFalse(client.settings().toString().contains("test-only"))
        }
    }
    @Test fun `explicit max tokens and length finish preserved`() {
        withServer(valid.replace("\"stop\"", "\"length\"")) { client, requests ->
            assertEquals("length", client.complete(listOf(LlmMessage("user", "Вопрос")), 1200).finishReason)
            assertEquals(1200, jacksonObjectMapper().readTree(requests.single()).path("max_tokens").asInt())
        }
    }
    @Test fun `bad response and empty final content never treated as answer`() {
        listOf("not-json", "{}", valid.replace("Ответ", ""), valid.replace("\"total_tokens\":15", "\"total_tokens\":16"), valid.replace("\"prompt_tokens\":10", "\"prompt_tokens\":-1")).forEach { body ->
            withServer(body) { client, _ -> assertThrows(LabException::class.java) { client.complete(listOf(LlmMessage("user", "Вопрос")), null) } }
        }
    }
    @Test fun `upstream error body not exposed and no retry`() {
        withServer("secret test-only raw echoed body", 401) { client, requests ->
            val error = assertThrows(LabException::class.java) { client.complete(listOf(LlmMessage("user", "Вопрос")), null) }
            assertFalse(error.message!!.contains("test-only"))
            assertEquals(1, requests.size)
        }
    }
    @Test fun `missing key fails without network request`() {
        val client = DeepSeekLlmClient(DeepSeekProperties(), jacksonObjectMapper())
        assertFalse(client.settings().configured)
        assertEquals("llm_not_configured", assertThrows(LabException::class.java) { client.complete(listOf(LlmMessage("user", "Вопрос")), null) }.code)
    }
}
