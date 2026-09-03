package com.example.aichat.task.day3.reasoning.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Содержит неизменяемые настройки Judge. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JudgeConfig(
        String instruction,
        Integer maxTokens,
        Integer maxAttempts,
        Integer maxRetryTokens
) {
    public void validate(String profileId) {
        MetaPromptConfig.requireText(instruction, "judge instruction", profileId);
        MetaPromptConfig.validateTokenLimit(maxTokens, "judge max_tokens", profileId);
        MetaPromptConfig.validateTokenLimit(maxRetryTokens, "judge max_retry_tokens", profileId);
        if (maxAttempts == null || maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("Invalid judge max_attempts: " + profileId);
        }
    }
}
