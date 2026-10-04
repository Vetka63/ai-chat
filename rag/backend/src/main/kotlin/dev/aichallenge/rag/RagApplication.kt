package dev.aichallenge.rag

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.config.DeepSeekProperties

/** Запускает отдельную RAG-лабораторию, не затрагивая прежних агентов. */
@SpringBootApplication
@EnableConfigurationProperties(RagProperties::class, DeepSeekProperties::class)
class RagApplication

/** Точка входа JVM-приложения. */
fun main(args: Array<String>) { runApplication<RagApplication>(*args) }
