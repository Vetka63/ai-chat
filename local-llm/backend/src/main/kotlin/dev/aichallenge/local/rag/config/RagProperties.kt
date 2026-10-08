package dev.aichallenge.local.rag.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** Отдельная БД нового корпуса и явное разрешение облачного сравнения; ключ не выдаётся браузеру. */
@Component
@ConfigurationProperties("rag")
class RagProperties {
    var databasePath: String = "./data/rag.sqlite"
    var embeddingModel: String = "qwen3-embedding:0.6b"
    var cloudEnabled: Boolean = false
    var deepseekBaseUrl: String = "https://api.deepseek.com"
    var deepseekApiKey: String = ""
    var deepseekModels: List<String> = listOf("deepseek-flash", "deepseek-v4-pro")
    var cloudTimeoutSeconds: Long = 180
}
