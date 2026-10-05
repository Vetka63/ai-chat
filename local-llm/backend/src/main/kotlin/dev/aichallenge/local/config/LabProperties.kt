package dev.aichallenge.local.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Серверные настройки локального runtime; ключи облачных провайдеров не используются. */
@Component
@ConfigurationProperties("local-llm")
class LabProperties {
    var baseUrl: String = "http://localhost:11436"
    var models: List<String> = listOf("qwen3:8b")
    var databasePath: String = "./data/lab.sqlite"
    var timeoutSeconds: Long = 300
    var contextSize: Int = 8192
}
