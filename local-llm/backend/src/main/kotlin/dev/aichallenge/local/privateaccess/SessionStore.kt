package dev.aichallenge.local.privateaccess

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** Временные сессии одного владельца. В памяти хранится хеш cookie, а не её исходное значение. */
@Component
class SessionStore(private val config: PrivateProperties) {
    private val sessions = linkedMapOf<String, Instant>()
    internal var now: () -> Instant = { Instant.now() }

    /** Сравнивает SHA-256 паролей за постоянное время; значение пароля никогда не логируется. */
    fun accepts(password: String): Boolean = MessageDigest.isEqual(digest(password), digest(config.password))

    /** Создаёт независимую сессию; не более 16 устройств/вкладок удерживают серверную память. */
    @Synchronized fun create(): String {
        cleanup()
        while (sessions.size >= 16) sessions.remove(sessions.keys.first())
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[key(token)] = now().plusSeconds(config.sessionSeconds)
        return token
    }

    /** Проверяет срок действия; перезапуск backend аннулирует сессии, но не историю SQLite. */
    @Synchronized fun valid(token: String?): Boolean {
        cleanup()
        return token != null && sessions.containsKey(key(token))
    }
    @Synchronized fun revoke(token: String?) { token?.let { sessions.remove(key(it)) } }
    private fun cleanup() { val time = now(); sessions.entries.removeIf { !it.value.isAfter(time) } }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    private fun key(text: String) = Base64.getEncoder().encodeToString(digest(text))
}
