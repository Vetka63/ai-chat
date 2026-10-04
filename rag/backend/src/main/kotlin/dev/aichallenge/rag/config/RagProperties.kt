package dev.aichallenge.rag.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Локальные пути и настройки embedding-runtime; генеративных моделей и секретов в дне 21 нет. */
@ConfigurationProperties("rag")
data class RagProperties(
    val corpusPath: String,
    val databasePath: String,
    val ollamaUrl: String,
    val embeddingModel: String,
    val batchSize: Int = 8,
    val embedTimeoutSeconds: Long = 180,
)
