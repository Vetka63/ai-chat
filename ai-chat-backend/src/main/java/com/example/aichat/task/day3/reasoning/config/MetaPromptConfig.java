package com.example.aichat.task.day3.reasoning.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Содержит неизменяемые настройки MetaPrompt. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MetaPromptConfig(
        String builderInstruction,
        String solverInstruction,
        Integer builderMaxTokens,
        Integer solverMaxTokens,
        Integer maxGeneratedPromptLength
) {
    public void validate(String profileId) {
        requireText(builderInstruction, "builder_instruction", profileId);
        requireText(solverInstruction, "solver_instruction", profileId);
        validateTokenLimit(builderMaxTokens, "builder_max_tokens", profileId);
        validateTokenLimit(solverMaxTokens, "solver_max_tokens", profileId);
        if (maxGeneratedPromptLength == null
                || maxGeneratedPromptLength < 100
                || maxGeneratedPromptLength > 20_000) {
            throw new IllegalArgumentException("Invalid max_generated_prompt_length: " + profileId);
        }
    }

    static void requireText(String value, String field, String profileId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field + ": " + profileId);
        }
    }

    static void validateTokenLimit(Integer value, String field, String profileId) {
        if (value == null || value < 1 || value > 8_000) {
            throw new IllegalArgumentException("Invalid " + field + ": " + profileId);
        }
    }
}
