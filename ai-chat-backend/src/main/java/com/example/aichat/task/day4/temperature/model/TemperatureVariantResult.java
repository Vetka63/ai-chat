package com.example.aichat.task.day4.temperature.model;

import com.example.aichat.common.llm.model.AggregateLlmMetrics;
import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.task.day4.temperature.config.TemperatureVariantConfig;
import com.example.aichat.task.day4.temperature.enums.TemperatureResultStatus;

import java.util.List;

/** Содержит ответ или безопасное описание ошибки для одной температуры. */
public record TemperatureVariantResult(
        String id,
        String title,
        String description,
        double temperature,
        TemperatureResultStatus status,
        String answer,
        AggregateLlmMetrics metrics,
        String model,
        String finishReason,
        String error
) {
    public static TemperatureVariantResult success(
            TemperatureVariantConfig variant,
            LlmResult result,
            long elapsedMs
    ) {
        return new TemperatureVariantResult(
                variant.id(),
                variant.title(),
                variant.description(),
                variant.value(),
                TemperatureResultStatus.SUCCESS,
                result.content(),
                AggregateLlmMetrics.from(List.of(result), elapsedMs),
                result.model(),
                result.finishReason(),
                null
        );
    }

    public static TemperatureVariantResult failure(
            TemperatureVariantConfig variant,
            long elapsedMs
    ) {
        return new TemperatureVariantResult(
                variant.id(),
                variant.title(),
                variant.description(),
                variant.value(),
                TemperatureResultStatus.ERROR,
                null,
                AggregateLlmMetrics.from(List.of(), elapsedMs).withApiCalls(1),
                null,
                null,
                "Не удалось получить ответ DeepSeek"
        );
    }
}
