package com.example.aichat.task.day4.temperature.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Содержит инструкцию, модель и лимиты автоматического судьи Дня 4. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TemperatureJudgeConfig(
        TemperatureJudgeLlmConfig llm,
        String instruction,
        Integer maxTokens,
        Integer maxAttempts,
        Integer maxRetryTokens
) {
    public void validate(String profileId) {
        if (llm == null) {
            throw new IllegalArgumentException("Missing temperature judge LLM config: " + profileId);
        }
        llm.validate(profileId);
        requireText(instruction, "temperature judge instruction", profileId);
        validateTokenLimit(maxTokens, "temperature judge max_tokens", profileId);
        validateTokenLimit(maxRetryTokens, "temperature judge max_retry_tokens", profileId);
        if (maxAttempts == null || maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("Invalid temperature judge max_attempts: " + profileId);
        }
    }

    private static void requireText(String value, String field, String profileId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field + ": " + profileId);
        }
    }

    private static void validateTokenLimit(Integer value, String field, String profileId) {
        if (value == null || value < 1 || value > 8_000) {
            throw new IllegalArgumentException("Invalid " + field + ": " + profileId);
        }
    }
}
