package dev.aichallenge.local

import dev.aichallenge.local.chat.*
import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.generation.*
import dev.aichallenge.local.privateaccess.*
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class PrivateServiceTest {
    @TempDir lateinit var folder: Path
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private fun properties() = PrivateProperties().also { it.enabled = true; it.password = "test-only-password-not-a-real-secret-32chars" }
    private fun run(filter: PrivateAccessFilter, path: String = "/api/v1/chats", method: String = "GET",
        token: String? = null, origin: String? = null, marker: Boolean = true, body: Any? = null): MockHttpServletResponse {
        val request = MockHttpServletRequest(method, path).also {
            it.servletPath = path
            if (token != null) it.setCookies(Cookie(PrivateAccessFilter.COOKIE, token))
            if (origin != null) it.addHeader("Origin", origin)
            if (marker) it.addHeader("X-Day30-Request", "1")
            if (body != null) { it.contentType = "application/json"; it.setContent(mapper.writeValueAsBytes(body)) }
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, result -> (result as MockHttpServletResponse).status = 204 })
        return response
    }
    @Test fun `enabled service fails startup without strong local password`() {
        val config = properties(); config.password = ""
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
        config.password = "short"; assertThrows(IllegalArgumentException::class.java) { config.validate() }
        properties().validate()
    }
    @Test fun `unauthenticated API and reports cannot read history or start models`() {
        val config = properties(); val filter = PrivateAccessFilter(config, SessionStore(config), LabProperties(), mapper)
        listOf("/api/v1/chats", "/api/v1/runtime", "/api/v1/runs/a/report").forEach { assertEquals(401, run(filter, it).status) }
        assertEquals(204, run(filter, "/actuator/health").status)
        val settings = run(filter, PrivateAccessFilter.SESSION)
        assertEquals(200, settings.status); assertFalse(mapper.readTree(settings.contentAsString).path("authenticated").asBoolean())
        assertFalse(settings.contentAsString.contains(config.password))
    }
    @Test fun `login sets HttpOnly Strict cookie and logout revokes it`() {
        val config = properties(); val store = SessionStore(config); val filter = PrivateAccessFilter(config, store, LabProperties(), mapper)
        val login = run(filter, PrivateAccessFilter.SESSION, "POST", body = mapOf("password" to config.password))
        assertEquals(200, login.status)
        val cookie = login.getHeader("Set-Cookie")!!
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Strict")); assertFalse(cookie.contains(config.password))
        val token = cookie.substringAfter("=").substringBefore(";")
        assertEquals(204, run(filter, token = token).status)
        assertEquals(204, run(filter, PrivateAccessFilter.SESSION, "DELETE", token = token).status)
        assertEquals(401, run(filter, token = token).status)
    }
    @Test fun `cross site mutations and missing custom header rejected even with valid cookie`() {
        val config = properties(); val store = SessionStore(config); val filter = PrivateAccessFilter(config, store, LabProperties(), mapper)
        val token = store.create()
        assertEquals(403, run(filter, method = "DELETE", token = token, origin = "https://evil.example").status)
        assertEquals(403, run(filter, method = "POST", token = token, marker = false).status)
        assertEquals(204, run(filter, method = "POST", token = token, origin = "http://127.0.0.1:8583").status)
        assertEquals(401, run(filter, method = "POST", token = "forged").status)
    }
    @Test fun `login throttle cannot be bypassed with another cookie and never echoes password`() {
        val config = properties(); val filter = PrivateAccessFilter(config, SessionStore(config), LabProperties(), mapper)
        repeat(5) { assertEquals(401, run(filter, PrivateAccessFilter.SESSION, "POST", body = mapOf("password" to "wrong")).status) }
        val limited = run(filter, PrivateAccessFilter.SESSION, "POST", body = mapOf("password" to config.password))
        assertEquals(429, limited.status); assertNotNull(limited.getHeader("Retry-After")); assertFalse(limited.contentAsString.contains(config.password))
    }
    @Test fun `expiry and server restart invalidate sessions without storing raw tokens`() {
        val config = properties(); val store = SessionStore(config)
        var instant = Instant.parse("2026-10-11T00:00:00Z"); store.now = { instant }
        val token = store.create(); assertTrue(store.valid(token)); assertFalse(SessionStore(config).valid(token))
        instant = instant.plusSeconds(config.sessionSeconds); assertFalse(store.valid(token))
    }
    @Test fun `sliding minute boundary permits new request and rejected call does not occupy bucket`() {
        var time = 0L; val limiter = MinuteLimiter(2) { time }
        limiter.acquire(); time = 1000; limiter.acquire()
        assertEquals(59, assertThrows(RateLimited::class.java) { limiter.acquire() }.retryAfter)
        time = 59999; assertThrows(RateLimited::class.java) { limiter.acquire() }
        time = 60000; limiter.acquire(); assertThrows(RateLimited::class.java) { limiter.acquire() }
        time = 61000; limiter.acquire()
    }
    @Test fun `byte guard counts UTF8 and full history instead of treating characters as tokens`() {
        val config = properties().also { it.contextBytes = 4 }; val limits = ServiceLimits(config)
        limits.context(listOf(LlmMessage(LlmRole.USER, "ёж")))
        assertThrows(ContextRejected::class.java) { limits.context(listOf(LlmMessage(LlmRole.USER, "ёж!"))) }
        assertThrows(ContextRejected::class.java) { limits.context(listOf(LlmMessage(LlmRole.SYSTEM, "a"), LlmMessage(LlmRole.USER, "ёж"))) }
    }
    @Test fun `replay does not consume quota and rate or size rejection does not persist user`() {
        val config = properties().also { it.generationsPerMinute = 1; it.contextBytes = 1500 }
        val lab = LabProperties().also { it.databasePath = folder.resolve("private.sqlite").toString() }
        val repo = ChatRepository(lab, mapper); var calls = 0
        val runtime = object : LocalLlmRuntime {
            override fun info() = RuntimeInfo(true, "test", lab.models, emptyList())
            override fun generate(request: GenerateRequest) = error("not used")
            override fun chat(request: LlmChatRequest): Generation { calls++; return Generation("OK", null, "stop", 30, 1, null, null, null) }
        }
        val service = ChatService(repo, runtime, ChatContextBuilder(), GenerationGate(), lab, ServiceLimits(config))
        val chat = service.create(CreateChatRequest("qwen3:8b"))
        val request = SendMessageRequest(UUID.randomUUID().toString(), "hello")
        val saved = service.send(chat.id, request); assertEquals(saved, service.send(chat.id, request)); assertEquals(1, calls)
        assertThrows(RateLimited::class.java) { service.send(chat.id, request.copy(requestId = UUID.randomUUID().toString())) }
        assertThrows(ContextRejected::class.java) { service.send(chat.id, request.copy(requestId = UUID.randomUUID().toString(), content = "x".repeat(2000))) }
        assertEquals(2, repo.find(chat.id)!!.messages.size); assertEquals(1, calls)
    }
}
