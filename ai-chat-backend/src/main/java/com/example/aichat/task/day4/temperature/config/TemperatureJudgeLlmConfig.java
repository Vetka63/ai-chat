package com.example.aichat.task.day4.temperature.config;

import com.example.aichat.common.llm.model.LlmRequestOverrides;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Set;

/** Задаёт параметры DeepSeek, используемые только автоматическим судьёй Дня 4. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TemperatureJudgeLlmConfig(
        String model,
        String thinking,
        String reasoningEffort,
        Double temperature,
        Double topP
) {
    public void validate(String profileId) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Missing temperature judge model: " + profileId);
        }
        if (thinking != null && !Set.of("enabled", "disabled").contains(thinking)) {
            throw new IllegalArgumentException("Invalid temperature judge thinking mode: " + profileId);
        }
        if (reasoningEffort != null && !Set.of("low", "high", "max").contains(reasoningEffort)) {
            throw new IllegalArgumentException("Invalid temperature judge reasoning effort: " + profileId);
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("Invalid temperature judge temperature: " + profileId);
        }
        if (topP != null && (topP < 0 || topP > 1)) {
            throw new IllegalArgumentException("Invalid temperature judge top_p: " + profileId);
        }
    }

    public LlmRequestOverrides toOverrides() {
        return new LlmRequestOverrides(model.trim(), thinking, reasoningEffort, temperature, topP);
    }
}
