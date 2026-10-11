package dev.aichallenge.local.privateaccess

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Настройки дня 30: один владелец, локальный секрет и отдельные серверные ограничения. */
@Component
@ConfigurationProperties("private-service")
class PrivateProperties {
    var enabled = false
    var password = ""
    var allowedOrigins: List<String> = listOf("http://127.0.0.1:8583", "http://localhost:8583")
    var sessionSeconds = 28800L
    var secureCookie = false
    var generationsPerMinute = 10
    var contextBytes = 24576

    /** Ошибка конфигурации закрывает сервис, а не запускает его с пустым паролем. */
    @PostConstruct fun validate() {
        if (!enabled) return
        require(password.length >= 32) { "Для дня 30 задайте локальный пароль длиной минимум 32 символа." }
        require(sessionSeconds in 60..86400 && generationsPerMinute in 1..120 && contextBytes in 512..1048576)
        require(allowedOrigins.isNotEmpty() && allowedOrigins.none { it == "*" })
    }
}
