package com.example.aichat.task.day5.modelcomparison.service;

import com.example.aichat.common.llm.model.LlmMetrics;
import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.llm.model.LlmUsage;
import com.example.aichat.task.day5.modelcomparison.config.ModelPricingConfig;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Рассчитывает сопоставимую стоимость вызова по тарифу выбранной модели. */
@Component
public class ModelCostCalculator {

    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000);

    public LlmResult apply(LlmResult result, ModelPricingConfig pricing) {
        var metrics = result.metrics();
        var cost = estimate(metrics.usage(), pricing);
        return new LlmResult(
                result.content(),
                result.model(),
                result.finishReason(),
                new LlmMetrics(metrics.durationMs(), metrics.usage(), cost)
        );
    }

    public BigDecimal estimate(LlmUsage usage, ModelPricingConfig pricing) {
        var cacheHitTokens = usage.promptCacheHitTokens();
        var cacheMissTokens = usage.promptCacheMissTokens();
        var classifiedPromptTokens = cacheHitTokens + cacheMissTokens;
        if (classifiedPromptTokens < usage.promptTokens()) {
            cacheMissTokens += usage.promptTokens() - classifiedPromptTokens;
        }

        return tokenCost(cacheMissTokens, pricing.promptCacheMissPerMillionUsd())
                .add(tokenCost(cacheHitTokens, pricing.promptCacheHitPerMillionUsd()))
                .add(tokenCost(usage.completionTokens(), pricing.outputPerMillionUsd()))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal tokenCost(long tokens, BigDecimal pricePerMillion) {
        return BigDecimal.valueOf(tokens)
                .multiply(pricePerMillion)
                .divide(TOKENS_PER_MILLION, 12, RoundingMode.HALF_UP);
    }
}
