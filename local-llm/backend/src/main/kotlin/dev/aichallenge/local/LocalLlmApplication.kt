package dev.aichallenge.local

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Запускает отдельный HTTP backend лаборатории локальных моделей. */
@SpringBootApplication
class LocalLlmApplication

fun main(args: Array<String>) { runApplication<LocalLlmApplication>(*args) }
