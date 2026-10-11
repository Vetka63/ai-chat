package dev.aichallenge.local.privateaccess

import dev.aichallenge.local.generation.LlmMessage
import org.springframework.stereotype.Component
import kotlin.math.ceil

/** Скользящее минутное окно на монотонных часах, без отдельного bucket для каждой cookie/IP. */
class MinuteLimiter(private val limit: Int, private val milliseconds: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val accepted = ArrayDeque<Long>()
    @Synchronized fun acquire() {
        val now = milliseconds()
        while (accepted.isNotEmpty() && now - accepted.first() >= 60000) accepted.removeFirst()
        if (accepted.size >= limit) throw RateLimited(ceil((60000 - (now - accepted.first())) / 1000.0).toInt().coerceAtLeast(1))
        accepted.addLast(now)
    }
}

/** Общий лимит владельца: новая cookie, другой IP и endpoint retry не позволяют его обойти. */
@Component
class ServiceLimits(private val config: PrivateProperties) {
    private val generations by lazy { MinuteLimiter(config.generationsPerMinute) }
    fun generation() { if (config.enabled) generations.acquire() }

    /** Размер истории — байты UTF-8, НЕ точный подсчёт токенов. Ничего не вырезается из диалога. */
    fun context(messages: List<LlmMessage>) {
        if (!config.enabled) return
        val bytes = messages.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }
        if (bytes > config.contextBytes) throw ContextRejected(
            "История с системной инструкцией занимает $bytes байт UTF-8, сервер допускает ${config.contextBytes}. " +
                "Сообщение не отправлено модели. История не удалена: сократите запрос или начните новый чат.")
    }
}

class RateLimited(val retryAfter: Int) : RuntimeException("Лимит запросов исчерпан. Повторите через $retryAfter с; автоматического повтора нет.")
class ContextRejected(message: String) : RuntimeException(message)
