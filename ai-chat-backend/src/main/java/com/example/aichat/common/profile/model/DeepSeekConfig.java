package com.example.aichat.common.profile.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Set;

/** Immutable configuration model for DeepSeek settings. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeepSeekConfig(
        String model,
        String thinking,
        String reasoningEffort,
        Double temperature,
        Double topP
) {
    public void validate(String agentId) {
        if (thinking != null && !Set.of("enabled", "disabled").contains(thinking)) {
            throw new IllegalArgumentException("Invalid thinking mode: " + agentId);
        }
        if (reasoningEffort != null && !Set.of("low", "high", "max").contains(reasoningEffort)) {
            throw new IllegalArgumentException("Invalid reasoning effort: " + agentId);
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("Invalid temperature: " + agentId);
        }
        if (topP != null && (topP < 0 || topP > 1)) {
            throw new IllegalArgumentException("Invalid top_p: " + agentId);
        }
    }
}
