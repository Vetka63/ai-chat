package com.example.aichat.task.day5.modelcomparison.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Содержит промпт, модель и пределы повторных попыток судьи Дня 5. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelComparisonJudgeConfig(
        ModelComparisonJudgeLlmConfig llm,
        String instruction,
        Integer maxTokens,
        Integer maxAttempts,
        Integer maxRetryTokens
) {
    public void validate(String profileId) {
        if (llm == null) {
            throw new IllegalArgumentException("Missing model comparison judge LLM config: " + profileId);
        }
        llm.validate(profileId);
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("Missing model comparison judge instruction: " + profileId);
        }
        validateTokenLimit(maxTokens, "judge max_tokens", profileId);
        validateTokenLimit(maxRetryTokens, "judge max_retry_tokens", profileId);
        if (maxAttempts == null || maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("Invalid model comparison judge max_attempts: " + profileId);
        }
    }

    private static void validateTokenLimit(Integer value, String field, String profileId) {
        if (value == null || value < 1 || value > 8_000) {
            throw new IllegalArgumentException("Invalid " + field + ": " + profileId);
        }
    }
}
