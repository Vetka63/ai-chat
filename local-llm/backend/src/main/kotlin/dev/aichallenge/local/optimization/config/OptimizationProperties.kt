package dev.aichallenge.local.optimization.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Выбранная при запуске локальная модель и закрытые серверные настройки облачного провайдера. */
@Component
@ConfigurationProperties("optimization")
class OptimizationProperties {
    var localModel = "qwen3:4b-q4_K_M"
    var databasePath = "./data/optimization.sqlite"
    var cloudEnabled = false
    var deepseekApiKey = ""
    var deepseekBaseUrl = "https://api.deepseek.com"
    var cloudModels: List<String> = listOf("deepseek-flash", "deepseek-v4-pro")
    var timeoutSeconds: Long = 180
}
