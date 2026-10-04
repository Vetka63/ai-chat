package dev.aichallenge.rag

import com.sun.net.httpserver.HttpServer
import dev.aichallenge.rag.answering.adapters.DeepSeekLlmClient
import dev.aichallenge.rag.answering.models.AnswerSettings
import dev.aichallenge.rag.answering.models.LlmCompletion
import dev.aichallenge.rag.answering.models.LlmMessage
import dev.aichallenge.rag.answering.ports.LlmClient
import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.config.DeepSeekProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress

/** Локальный HTTP fixture проверяет протокол и защиту ключа без обращения к DeepSeek. */
class DeepSeekAdapterTest {
    private val valid = """{"id":"request","model":"deepseek-flash","choices":[{"finish_reason":"stop","message":{"content":"Ответ"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"prompt_cache_hit_tokens":4,"prompt_cache_miss_tokens":6}}"""
    private fun withServer(
        body: String, status: Int = 200,
        supportModel: String = "deepseek-v4-pro", supportReasoningEffort: String = "high", supportThinking: Boolean = true, groundingThinking: Boolean = false,
        action: (DeepSeekLlmClient, MutableList<String>) -> Unit,
    ) {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            requests.add(exchange.requestBody.bufferedReader().readText())
            assertEquals("Bearer test-only", exchange.requestHeaders.getFirst("Authorization"))
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val properties = DeepSeekProperties("test-only", "http://127.0.0.1:${server.address.port}", supportModel = supportModel, supportReasoningEffort = supportReasoningEffort, supportThinkingEnabled = supportThinking, groundingThinkingEnabled = groundingThinking)
            action(DeepSeekLlmClient(properties, jacksonObjectMapper()), requests)
        }
        finally { server.stop(0) }
    }
    @Test fun `optional max tokens omitted and thinking disabled`() {
        withServer(valid) { client, requests ->
            val result = client.complete(listOf(LlmMessage("user", "Вопрос")), null)
            val request = jacksonObjectMapper().readTree(requests.single())
            assertFalse(request.has("max_tokens"))
            assertFalse(request.has("response_format"))
            assertFalse(request.has("reasoning_effort"))
            assertEquals("deepseek-flash", request.path("model").asText())
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
    @Test fun `empty structured completion preserves measured usage for fail closed validators`() {
        withServer(valid.replace("Ответ", "").replace("\"stop\"", "\"length\"")) { client, requests ->
            val result = client.completeVerifiedJson(listOf(LlmMessage("user", "JSON")), 8192)
            assertEquals("", result.content)
            assertEquals("length", result.finishReason)
            assertEquals(15L, result.usage!!.totalTokens)
            assertEquals(1, requests.size)
        }
    }
    @Test fun `missing key fails without network request`() {
        val client = DeepSeekLlmClient(DeepSeekProperties(), jacksonObjectMapper())
        assertFalse(client.settings().configured)
        assertEquals("llm_not_configured", assertThrows(LabException::class.java) { client.complete(listOf(LlmMessage("user", "Вопрос")), null) }.code)
    }
    @Test fun `JSON object requested only for technical generation`() {
        withServer(valid) { client, requests ->
            client.completeJson(listOf(LlmMessage("user", "Верни JSON")), 512)
            val request = jacksonObjectMapper().readTree(requests.single())
            assertEquals("json_object", request.path("response_format").path("type").asText())
            assertEquals(512, request.path("max_tokens").asInt())
            assertEquals("deepseek-flash", request.path("model").asText())
            assertEquals("disabled", request.path("thinking").path("type").asText())
            assertFalse(request.has("reasoning_effort"))
        }
    }
    @Test fun `JSON generation with no local cap omits max tokens`() {
        withServer(valid) { client, requests ->
            client.completeJson(listOf(LlmMessage("user", "Верни JSON")), null)
            val request = jacksonObjectMapper().readTree(requests.single())
            assertEquals("json_object", request.path("response_format").path("type").asText())
            assertFalse(request.has("max_tokens"))
        }
    }
    @Test fun `verified JSON uses isolated thinking model and preserves full completion usage`() {
        val body = valid.replace("deepseek-flash", "provider-support-model")
            .replace("\"content\":\"Ответ\"", "\"content\":\"Ответ\",\"reasoning_content\":\"private-reasoning\"")
            .replace("\"completion_tokens\":5", "\"completion_tokens\":5,\"completion_tokens_details\":{\"reasoning_tokens\":3}")
        withServer(body) { client, requests ->
            val messages = listOf(LlmMessage("user", "Проверь утверждение и верни JSON"))
            val result = client.completeVerifiedJson(messages, 8192)
            val request = jacksonObjectMapper().readTree(requests.single())
            assertEquals("deepseek-v4-pro", request.path("model").asText())
            assertEquals("enabled", request.path("thinking").path("type").asText())
            assertEquals("high", request.path("reasoning_effort").asText())
            assertEquals("json_object", request.path("response_format").path("type").asText())
            assertEquals(8192, request.path("max_tokens").asInt())
            assertFalse(request.path("stream").asBoolean())
            assertEquals(messages.single().content, request.path("messages").first().path("content").asText())
            assertEquals("provider-support-model", result.model)
            assertEquals("Ответ", result.content)
            assertEquals(5L, result.usage!!.completionTokens)
            assertEquals(15L, result.usage!!.totalTokens)
            assertFalse(result.toString().contains("private-reasoning"))
            assertFalse(result.toString().contains("test-only"))
            assertEquals("deepseek-flash", client.settings().model)
            assertEquals("disabled", client.settings().thinking)
        }
    }
    @Test fun `grounded generator uses Pro without changing comparison model or imposing output cap`() {
        withServer(valid) { client, requests ->
            val messages = listOf(LlmMessage("user", "Ответ с цитатами JSON"))
            client.completeGroundedJson(messages, null)
            client.completeGroundedJson(messages, 2400)
            client.completeJson(messages, null)
            val grounded = requests.take(2).map { jacksonObjectMapper().readTree(it) }
            grounded.forEach {
                assertEquals("deepseek-v4-pro", it.path("model").asText())
                assertEquals("disabled", it.path("thinking").path("type").asText())
                assertEquals("json_object", it.path("response_format").path("type").asText())
                assertFalse(it.has("reasoning_effort"))
            }
            assertFalse(grounded[0].has("max_tokens"))
            assertEquals(2400, grounded[1].path("max_tokens").asInt())
            assertEquals("deepseek-flash", jacksonObjectMapper().readTree(requests[2]).path("model").asText())
            assertEquals("deepseek-flash", client.settings().model)
        }
    }
    @Test fun `support configuration and optional cap do not leak into regular requests`() {
        withServer(valid, supportModel = "fixture-support-model", supportReasoningEffort = "high") { client, requests ->
            val messages = listOf(LlmMessage("user", "Верни JSON"))
            client.completeVerifiedJson(messages, null)
            client.completeJson(messages, 512)
            client.complete(messages, null)
            val verified = jacksonObjectMapper().readTree(requests[0])
            assertEquals("fixture-support-model", verified.path("model").asText())
            assertEquals("high", verified.path("reasoning_effort").asText())
            assertFalse(verified.has("max_tokens"))
            requests.drop(1).forEach { raw ->
                val request = jacksonObjectMapper().readTree(raw)
                assertEquals("deepseek-flash", request.path("model").asText())
                assertEquals("disabled", request.path("thinking").path("type").asText())
                assertFalse(request.has("reasoning_effort"))
            }
        }
    }
    @Test fun `verified JSON default delegates to JSON for existing clients`() {
        val messages = listOf(LlmMessage("user", "Верни JSON"))
        val expected = LlmCompletion("fixture", "fixture-model", "{}", "stop", 1, null)
        val calls = mutableListOf<Pair<List<LlmMessage>, Int?>>()
        val client = object : LlmClient {
            override fun settings(): AnswerSettings = error("Settings are not needed")
            override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion = error("JSON override should be used")
            override fun completeJson(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion {
                calls += messages to maxOutputTokens
                return expected
            }
        }
        assertSame(expected, client.completeVerifiedJson(messages, 8192))
        assertSame(expected, client.completeVerifiedJson(messages, null))
        assertEquals(listOf(messages to 8192, messages to null), calls)
    }
    @Test fun `scope profile is Pro without reasoning and does not change support profile`() {
        withServer(valid) { client, requests ->
            client.completeScopeJson(listOf(LlmMessage("user", "JSON")), 16384)
            client.completeVerifiedJson(listOf(LlmMessage("user", "JSON")), 16384)
            val scope = jacksonObjectMapper().readTree(requests[0])
            assertEquals("deepseek-v4-pro", scope.path("model").asText())
            assertEquals("disabled", scope.path("thinking").path("type").asText())
            assertFalse(scope.has("reasoning_effort"))
            assertEquals(16384, scope.path("max_tokens").asInt())
            assertEquals("json_object", scope.path("response_format").path("type").asText())
            assertEquals("enabled", jacksonObjectMapper().readTree(requests[1]).path("thinking").path("type").asText())
        }
    }
    @Test fun `non thinking support remains Pro but omits reasoning effort`() {
        withServer(valid, supportThinking = false) { client, requests ->
            client.completeVerifiedJson(listOf(LlmMessage("user", "JSON")), 16384)
            val request = jacksonObjectMapper().readTree(requests.single())
            assertEquals("deepseek-v4-pro", request.path("model").asText())
            assertEquals("disabled", request.path("thinking").path("type").asText())
            assertFalse(request.has("reasoning_effort"))
        }
    }
    @Test fun `grounded reasoning is explicit and never enables thinking in baseline comparison`() {
        withServer(valid, groundingThinking = true) { client, requests ->
            client.completeGroundedJson(listOf(LlmMessage("user", "JSON")), null)
            client.completeJson(listOf(LlmMessage("user", "JSON")), null)
            val grounded = jacksonObjectMapper().readTree(requests[0])
            assertEquals("enabled", grounded.path("thinking").path("type").asText())
            assertEquals("high", grounded.path("reasoning_effort").asText())
            assertFalse(grounded.has("max_tokens"))
            val baseline = jacksonObjectMapper().readTree(requests[1])
            assertEquals("disabled", baseline.path("thinking").path("type").asText())
            assertFalse(baseline.has("reasoning_effort"))
        }
    }
}
