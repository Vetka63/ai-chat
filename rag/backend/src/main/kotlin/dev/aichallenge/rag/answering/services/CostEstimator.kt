package dev.aichallenge.rag.answering.services

import dev.aichallenge.rag.answering.models.*
import org.springframework.stereotype.Component

/** Снимок опубликованных тарифов. Диапазон не угадывает календарь праздников или реальное списание. */
@Component
class CostEstimator {
    fun estimate(model: String, usage: TokenUsage?): CostEstimate? {
        if (usage == null) return null
        val rates = when (model) {
            "deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp" -> doubleArrayOf(.003, .15, .6)
            "deepseek-v4-pro" -> doubleArrayOf(.022, .66, 1.98)
            else -> return null
        }
        val cacheKnown = usage.cacheHitTokens != null && usage.cacheMissTokens != null
        val minimumInput = if (cacheKnown) usage.cacheHitTokens!! * rates[0] + usage.cacheMissTokens!! * rates[1] else usage.promptTokens * rates[0]
        val maximumInput = if (cacheKnown) usage.cacheHitTokens!! * rates[0] + usage.cacheMissTokens!! * rates[1] else usage.promptTokens * rates[1]
        return CostEstimate((minimumInput + usage.completionTokens * rates[2]) / 1_000_000, (maximumInput + usage.completionTokens * rates[2]) * 2 / 1_000_000,
            "https://api-docs.deepseek.com/quick_start/pricing/", "2026-10-04", "Оценка USD по off-peak/peak тарифам, не счёт провайдера. " + if (cacheKnown) "Разделение cache hit/miss взято из API." else "Cache split неизвестен: диапазон включает неопределённость входных токенов.")
    }
}
