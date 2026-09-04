package com.example.aichat.task.day5.modelcomparison.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Set;

/** Задаёт DeepSeek-модель и параметры генерации автоматического судьи Дня 5. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelComparisonJudgeLlmConfig(
        String model,
        String thinking,
        String reasoningEffort,
        Double temperature,
        Double topP
) {
    public void validate(String profileId) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Missing model comparison judge model: " + profileId);
        }
        if (thinking != null && !Set.of("enabled", "disabled").contains(thinking)) {
            throw new IllegalArgumentException("Invalid model comparison judge thinking: " + profileId);
        }
        if (reasoningEffort != null && !Set.of("low", "high", "max").contains(reasoningEffort)) {
            throw new IllegalArgumentException("Invalid model comparison judge reasoning effort: " + profileId);
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("Invalid model comparison judge temperature: " + profileId);
        }
        if (topP != null && (topP <= 0 || topP > 1)) {
            throw new IllegalArgumentException("Invalid model comparison judge top_p: " + profileId);
        }
    }
}
