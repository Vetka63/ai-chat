package dev.aichallenge.local

import com.sun.net.httpserver.HttpServer
import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.generation.*
import dev.aichallenge.local.runtime.OllamaRuntime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress

class OllamaRuntimeTest {
    private val mapper = JsonMapper.builder().build()

    private fun withServer(answer: String = "Paris", reason: String = "stop", remote: Boolean = false, chatStatus: Int = 200, action: (OllamaRuntime, MutableList<String>) -> Unit) {
        val sent = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            val response = when (e.requestURI.path) {
                "/api/version" -> """{"version":"test"}"""
                "/api/tags" -> """{"models":[{"name":"qwen3:8b","size":5200000000,"details":{"parameter_size":"8B","quantization_level":"Q4_K_M"}${if (remote) ",\"remote_host\":\"cloud\"" else ""}}]}"""
                "/api/show" -> """{"capabilities":["completion"]}"""
                "/api/chat" -> { sent.add(String(e.requestBody.readAllBytes())); mapper.writeValueAsString(mapOf("done" to true, "done_reason" to reason, "message" to mapOf("content" to answer), "prompt_eval_count" to 10, "eval_count" to 5, "eval_duration" to 100000000L, "total_duration" to 200000000L, "load_duration" to 50000000L)) }
                else -> "{}"
            }
            val actualResponse = if (e.requestURI.path == "/api/chat" && chatStatus != 200)
                """{"error":"request (15161 tokens) exceeds the available context size (8192 tokens)"}""" else response
            val bytes = actualResponse.toByteArray(); e.responseHeaders.add("Content-Type", "application/json"); e.sendResponseHeaders(if (e.requestURI.path == "/api/chat") chatStatus else 200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { action(OllamaRuntime(LabProperties().also { it.baseUrl = "http://127.0.0.1:${server.address.port}" }, mapper), sent) } finally { server.stop(0) }
    }

    @Test fun `sends one real chat request with no implicit output limit`() = withServer { runtime, sent ->
        val result = runtime.generate(GenerateRequest("qwen3:8b", "hello"))
        assertEquals(1, sent.size)
        val body = mapper.readTree(sent.single())
        assertFalse(body.path("stream").asBoolean()); assertFalse(body.path("think").asBoolean())
        assertFalse(body.path("options").has("num_predict"))
        assertFalse(body.path("truncate").asBoolean()); assertFalse(body.path("shift").asBoolean())
        assertEquals("hello", body.path("messages").get(0).path("content").asText())
        assertEquals(200.0, result.totalMilliseconds); assertEquals(50.0, result.tokensPerSecond)
    }
    @Test fun `explicit output cap is sent to Ollama`() = withServer { runtime, sent ->
        runtime.generate(GenerateRequest("qwen3:8b", "hello", maxOutputTokens = 100))
        assertEquals(100, mapper.readTree(sent.single()).path("options").path("num_predict").asInt())
    }
    @Test fun `experimental context sampling and seed override defaults in real HTTP payload`() = withServer { runtime, sent ->
        runtime.chat(LlmChatRequest("qwen3:8b", listOf(LlmMessage(LlmRole.USER, "q")), contextSize = 2048, topP = .8, topK = 20, seed = 42))
        val options = mapper.readTree(sent.single()).path("options")
        assertEquals(2048, options.path("num_ctx").asInt()); assertEquals(42, options.path("seed").asInt())
        assertEquals(20, options.path("top_k").asInt()); assertEquals(.8, options.path("top_p").asDouble())
    }
    @Test fun `empty output is an error and is not retried`() = withServer(answer = "") { runtime, sent ->
        assertThrows(RuntimeFailure::class.java) { runtime.generate(GenerateRequest("qwen3:8b", "q")) }
        assertEquals(1, sent.size)
    }
    @Test fun `cloud model is rejected before chat request`() = withServer(remote = true) { runtime, sent ->
        assertThrows(InvalidModel::class.java) { runtime.generate(GenerateRequest("qwen3:8b", "q")) }
        assertTrue(sent.isEmpty())
    }
    @Test fun `unconfigured model is rejected before generation`() = withServer { runtime, sent ->
        assertThrows(InvalidModel::class.java) { runtime.generate(GenerateRequest("some-model", "q")) }
        assertTrue(sent.isEmpty())
    }
    @Test fun `dialogue forwards full ordered messages and rejects silent history truncation`() = withServer { runtime, sent ->
        val messages = listOf(LlmMessage(LlmRole.SYSTEM, "system"), LlmMessage(LlmRole.USER, "first"),
            LlmMessage(LlmRole.ASSISTANT, "answer"), LlmMessage(LlmRole.USER, "next"))
        runtime.chat(LlmChatRequest("qwen3:8b", messages))
        val body = mapper.readTree(sent.single())
        assertEquals(4, body.path("messages").size())
        assertEquals("assistant", body.path("messages").get(2).path("role").asText())
        assertEquals("next", body.path("messages").get(3).path("content").asText())
        assertFalse(body.path("truncate").asBoolean())
        assertFalse(body.path("shift").asBoolean())
    }
    @Test fun `provider context overflow becomes understandable error without retry`() = withServer(chatStatus = 400) { runtime, sent ->
        val error = assertThrows(RuntimeFailure::class.java) { runtime.generate(GenerateRequest("qwen3:8b", "q")) }
        assertTrue(error.message!!.contains("15161")); assertTrue(error.message!!.contains("8192"))
        assertTrue(error.message!!.contains("История сохранена")); assertFalse(error.message!!.contains("{\"error\""))
        assertEquals(1, sent.size)
    }
}
