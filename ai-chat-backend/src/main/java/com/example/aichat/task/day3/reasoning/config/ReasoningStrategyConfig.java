package com.example.aichat.task.day3.reasoning.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Immutable configuration model for ReasoningStrategy settings. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReasoningStrategyConfig(String instruction, Integer maxTokens) {
    public void validate(String profileId, boolean instructionRequired) {
        if (instructionRequired && (instruction == null || instruction.isBlank())) {
            throw new IllegalArgumentException("Reasoning instruction is required: " + profileId);
        }
        if (maxTokens == null || maxTokens < 1 || maxTokens > 8_000) {
            throw new IllegalArgumentException("Invalid reasoning max_tokens: " + profileId);
        }
    }
}
