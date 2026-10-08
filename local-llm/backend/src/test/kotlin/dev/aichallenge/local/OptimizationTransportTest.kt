package dev.aichallenge.local

import com.sun.net.httpserver.HttpServer
import dev.aichallenge.local.generation.*
import dev.aichallenge.local.optimization.config.OptimizationProperties
import dev.aichallenge.local.optimization.models.*
import dev.aichallenge.local.optimization.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress

class OptimizationTransportTest {
    private val mapper = JsonMapper.builder().build()
    private fun withCloud(status: Int = 200, action: (DeepSeekGateway, MutableList<Pair<String, String>>) -> Unit) {
        val sent = mutableListOf<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            sent += (exchange.requestHeaders.getFirst("Authorization") to String(exchange.requestBody.readAllBytes()))
            val text = if (status == 200) """{"model":"actual-cloud","choices":[{"finish_reason":"stop","message":{"content":"{}"}}],"usage":{"prompt_tokens":20,"completion_tokens":3}}"""
                else """{"error":"secret-key-and-internal-error"}"""
            val bytes = text.toByteArray(); exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val config = OptimizationProperties().also { it.cloudEnabled = true; it.deepseekApiKey = "secret-key-and-internal-error"; it.deepseekBaseUrl = "http://127.0.0.1:${server.address.port}" }
        try { action(DeepSeekGateway(config, mapper), sent) } finally { server.stop(0) }
    }
    @Test fun `cloud sends same messages one request and secret only in header`() = withCloud { gateway, sent ->
        val messages = listOf(LlmMessage(LlmRole.SYSTEM, "contract"), LlmMessage(LlmRole.USER, "incident"))
        val answer = gateway.generate("deepseek-v4-pro", messages, Parameters())
        assertEquals(1, sent.size); assertTrue(sent.single().first.contains("secret-key")); assertFalse(sent.single().second.contains("secret-key"))
        val body = mapper.readTree(sent.single().second)
        assertEquals(2, body.path("messages").size()); assertEquals("contract", body.path("messages").get(0).path("content").asText())
        assertFalse(body.has("seed")); assertFalse(body.has("num_ctx")); assertFalse(body.has("top_k")); assertFalse(body.has("top_p"))
        assertEquals("disabled", body.path("thinking").path("type").asText()); assertEquals(512, body.path("max_tokens").asInt())
        assertEquals("actual-cloud", answer.actualModel); assertEquals(20, answer.inputTokens); assertNull(answer.tokensPerSecond)
    }
    @Test fun `cloud thinking omits ineffective temperature and clamps topP explicitly`() = withCloud { gateway, sent ->
        gateway.generate("deepseek-flash", listOf(LlmMessage(LlmRole.USER, "incident")), Parameters(thinking = true, topP = .8))
        val body = mapper.readTree(sent.single().second)
        assertFalse(body.has("temperature")); assertEquals(.95, body.path("top_p").asDouble()); assertEquals("high", body.path("reasoning_effort").asText())
    }
    @Test fun `cloud HTTP failure is not retried and raw error secret is not returned`() = withCloud(status = 401) { gateway, sent ->
        val e = assertThrows(OptimizationFailure::class.java) { gateway.generate("deepseek-v4-pro", emptyList(), Parameters()) }
        assertEquals(1, sent.size); assertTrue(e.message!!.contains("401")); assertFalse(e.message!!.contains("secret-key"))
    }
    @Test fun `disabled cloud is rejected before any network operation`() {
        val gateway = DeepSeekGateway(OptimizationProperties().also { it.deepseekBaseUrl = "http://127.0.0.1:1" }, mapper)
        assertThrows(OptimizationFailure::class.java) { gateway.generate("deepseek-v4-pro", emptyList(), Parameters()) }
    }
    @Test fun `routing local keeps all experiment parameters and never calls cloud`() {
        var captured: LlmChatRequest? = null
        val local = object : LocalLlmRuntime {
            override fun info() = RuntimeInfo(true, "test", emptyList(), emptyList())
            override fun generate(request: GenerateRequest) = throw UnsupportedOperationException()
            override fun chat(request: LlmChatRequest): Generation { captured = request; return Generation("{}", null, "stop", 1, 1, null, null, null) }
        }
        val gateway = ProviderGateway(local, DeepSeekGateway(OptimizationProperties(), mapper))
        gateway.generate(Provider.LOCAL, "local", listOf(LlmMessage(LlmRole.USER, "q")), Parameters(temperature = .3, maxTokens = 256, contextSize = 2048, topP = .9, topK = 30, seed = 123))
        assertEquals(2048, captured?.contextSize); assertEquals(.3, captured?.temperature); assertEquals(123, captured?.seed)
        assertEquals(256, captured?.maxOutputTokens); assertEquals(30, captured?.topK)
    }
}
