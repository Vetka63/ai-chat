package com.example.aichat.task.day5.modelcomparison.model;

import com.example.aichat.common.llm.model.AggregateLlmMetrics;
import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.task.day5.modelcomparison.config.ModelVariantConfig;
import com.example.aichat.task.day5.modelcomparison.enums.ModelResultStatus;

import java.util.List;

/** Содержит ответ, метрики и ссылки для одной сравниваемой модели. */
public record ModelVariantResult(
        String id,
        String title,
        String description,
        String provider,
        String model,
        String modelUrl,
        String pricingUrl,
        String pricingLabel,
        ModelResultStatus status,
        String answer,
        AggregateLlmMetrics metrics,
        String responseModel,
        String finishReason,
        String error
) {
    public static ModelVariantResult success(
            ModelVariantConfig variant,
            LlmResult result,
            long elapsedMs
    ) {
        return new ModelVariantResult(
                variant.id(),
                variant.title(),
                variant.description(),
                variant.provider().id(),
                variant.model(),
                variant.modelUrl(),
                variant.pricingUrl(),
                variant.pricing().label(),
                ModelResultStatus.SUCCESS,
                result.content(),
                AggregateLlmMetrics.from(List.of(result), elapsedMs),
                result.model(),
                result.finishReason(),
                null
        );
    }

    public static ModelVariantResult failure(ModelVariantConfig variant, long elapsedMs) {
        return new ModelVariantResult(
                variant.id(),
                variant.title(),
                variant.description(),
                variant.provider().id(),
                variant.model(),
                variant.modelUrl(),
                variant.pricingUrl(),
                variant.pricing().label(),
                ModelResultStatus.ERROR,
                null,
                AggregateLlmMetrics.from(List.of(), elapsedMs).withApiCalls(1),
                null,
                null,
                "Не удалось получить ответ от " + variant.title()
        );
    }
}
