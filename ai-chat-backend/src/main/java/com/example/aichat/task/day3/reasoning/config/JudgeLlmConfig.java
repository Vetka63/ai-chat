package com.example.aichat.task.day3.reasoning.config;

import com.example.aichat.common.llm.model.LlmRequestOverrides;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Set;

/** Задаёт модель и режим рассуждения, используемые только автоматическим судьёй. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JudgeLlmConfig(
        String model,
        String thinking,
        String reasoningEffort
) {
    public void validate(String profileId) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Missing judge LLM model: " + profileId);
        }
        if (thinking != null && !Set.of("enabled", "disabled").contains(thinking)) {
            throw new IllegalArgumentException("Invalid judge thinking mode: " + profileId);
        }
        if (reasoningEffort != null && !Set.of("low", "high", "max").contains(reasoningEffort)) {
            throw new IllegalArgumentException("Invalid judge reasoning effort: " + profileId);
        }
    }

    public LlmRequestOverrides toOverrides() {
        return LlmRequestOverrides.model(model.trim(), thinking, reasoningEffort);
    }
}
