package dev.aichallenge.rag.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Серверный секрет не входит в DTO, логи и сгенерированный toString data class. */
@ConfigurationProperties("deepseek")
class DeepSeekProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val timeoutSeconds: Long = 120,
    val supportModel: String = "deepseek-v4-pro",
    val supportReasoningEffort: String = "high",
    val supportTimeoutSeconds: Long = 180,
    val groundingModel: String = "deepseek-v4-pro",
    val groundingTimeoutSeconds: Long = 180,
    val supportThinkingEnabled: Boolean = false,
)
