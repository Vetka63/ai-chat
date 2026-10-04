package dev.aichallenge.rag

import com.sun.net.httpserver.HttpServer
import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.embeddings.adapters.OllamaEmbeddingProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress

/** Проверяет HTTP-контракт адаптера без скачивания модели и без платных LLM-вызовов. */
class OllamaAdapterTest {
    private fun withRuntime(response: String, status: Int = 200, action: (OllamaEmbeddingProvider, MutableList<String>) -> Unit) {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/embed") { exchange ->
            requests.add(exchange.requestBody.bufferedReader().readText())
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/tags") { exchange ->
            val bytes = """{"models":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { action(OllamaEmbeddingProvider(RagProperties("unused", "unused", "http://127.0.0.1:${server.address.port}", "test"), jacksonObjectMapper()), requests) }
        finally { server.stop(0) }
    }
    @Test fun `sends truncate false and preserves returned token usage`() {
        withRuntime("""{"model":"test","embeddings":[[0.5,-0.5]],"prompt_eval_count":7}""") { provider, requests ->
            val result = provider.embed(listOf("Русский текст"))
            assertArrayEquals(floatArrayOf(.5f, -.5f), result.vectors.single())
            assertEquals(7L, result.inputTokens)
            val body = jacksonObjectMapper().readTree(requests.single())
            assertFalse(body.path("truncate").asBoolean())
            assertEquals("Русский текст", body.path("input")[0].asText())
        }
    }
    @Test fun `rejects wrong vector count model dimension and zero vectors`() {
        listOf(
            """{"model":"test","embeddings":[]}""" to "embedding_count",
            """{"model":"other","embeddings":[[1,0]]}""" to "embedding_model_mismatch",
            """{"model":"test","embeddings":[[0,0]]}""" to "embedding_invalid",
            """{"model":"test","embeddings":[["bad",0]]}""" to "embedding_invalid",
            """{"model":"test","embeddings":[[1,0],[1]]}""" to "embedding_dimension",
        ).forEach { (json, code) -> withRuntime(json) { provider, _ ->
            val input = if (code == "embedding_dimension") listOf("один", "два") else listOf("один")
            assertEquals(code, assertThrows(LabException::class.java) { provider.embed(input) }.code)
        } }
    }
    @Test fun `reports provider errors malformed json and missing model`() {
        withRuntime("failure", 500) { provider, _ -> assertEquals("ollama_error", assertThrows(LabException::class.java) { provider.embed(listOf("a")) }.code) }
        withRuntime("not JSON") { provider, _ -> assertEquals("ollama_invalid_json", assertThrows(LabException::class.java) { provider.embed(listOf("a")) }.code) }
        withRuntime("unused") { provider, _ -> assertEquals("model_missing", assertThrows(LabException::class.java) { provider.identity() }.code) }
    }
}
