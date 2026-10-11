package dev.aichallenge.local.privateaccess

import dev.aichallenge.local.config.LabProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/** Защищает API независимо от ZeroTier. Не доверяет X-Forwarded-For и заголовкам идентичности клиента. */
@Component
@Order(1)
class PrivateAccessFilter(private val config: PrivateProperties, private val sessions: SessionStore,
    private val lab: LabProperties, private val mapper: ObjectMapper) : OncePerRequestFilter() {
    private val loginLimit = MinuteLimiter(5)

    /** Сессия обязательна на всех API; health не содержит пользовательских данных. CORS не включён. */
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.servletPath
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("X-Content-Type-Options", "nosniff")
        if (path == "/actuator/health" && request.method == "GET") { chain.doFilter(request, response); return }
        if (!config.enabled && path != SESSION) { chain.doFilter(request, response); return }
        val token = request.cookies?.firstOrNull { it.name == COOKIE }?.value
        if (config.enabled && request.method !in setOf("GET", "HEAD")) {
            val origin = request.getHeader("Origin")
            if (request.getHeader("X-Day30-Request") != "1" || (origin != null && origin !in config.allowedOrigins)) {
                fail(response, 403, "Запрос отклонён: используйте интерфейс приватного сервиса."); return
            }
        }
        if (path == SESSION && request.method == "GET") {
            json(response, mapOf("enabled" to config.enabled, "authenticated" to (!config.enabled || sessions.valid(token)),
                "contextTokens" to lab.contextSize, "contextBytes" to config.contextBytes,
                "generationsPerMinute" to config.generationsPerMinute, "maxConcurrentGenerations" to 1,
                "sessionSeconds" to config.sessionSeconds)); return
        }
        if (path == SESSION && request.method == "POST" && config.enabled) {
            try { loginLimit.acquire() } catch (e: RateLimited) {
                response.setHeader("Retry-After", e.retryAfter.toString()); fail(response, 429, e.message!!); return
            }
            if (request.contentType?.substringBefore(';') != "application/json") { fail(response, 415, "Нужен JSON."); return }
            val bytes = request.inputStream.readNBytes(4097)
            if (bytes.size > 4096) { fail(response, 413, "Слишком большой запрос входа."); return }
            val password = try { mapper.readTree(bytes).path("password").asText("") } catch (_: Exception) {
                fail(response, 400, "Некорректный JSON."); return
            }
            if (!sessions.accepts(password)) { fail(response, 401, "Неверный пароль приватного чата."); return }
            response.addHeader("Set-Cookie", cookie(sessions.create(), config.sessionSeconds))
            json(response, mapOf("authenticated" to true)); return
        }
        if (config.enabled && !sessions.valid(token)) { fail(response, 401, "Войдите в приватный чат."); return }
        if (path == SESSION && request.method == "DELETE") {
            sessions.revoke(token); response.addHeader("Set-Cookie", cookie("", 0)); response.status = 204; return
        }
        chain.doFilter(request, response)
    }

    private fun cookie(token: String, seconds: Long) = ResponseCookie.from(COOKIE, token).httpOnly(true)
        .secure(config.secureCookie).sameSite("Strict").path("/").maxAge(seconds).build().toString()
    private fun fail(response: HttpServletResponse, status: Int, message: String) {
        response.status = status; json(response, mapOf("message" to message))
    }
    private fun json(response: HttpServletResponse, body: Any) {
        response.contentType = "application/json;charset=UTF-8"; response.writer.write(mapper.writeValueAsString(body))
    }
    companion object {
        const val SESSION = "/api/v1/private/session"
        const val COOKIE = "day30_session"
    }
}
