package com.example.aichat.common.profile.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Immutable configuration model for RequestGuard settings. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RequestGuardConfig(
        String type,
        String instruction,
        Integer maxTokens,
        Integer maxAttempts
) {
    public RequestGuardConfig {
        maxAttempts = maxAttempts == null ? 2 : maxAttempts;
    }

    public void validate(String agentId) {
        AgentProfile.requireText(type, "request_guard.type");
        AgentProfile.requireText(instruction, "request_guard.instruction");
        if (!type.matches("^[a-z][a-z0-9-]{1,31}$")) {
            throw new IllegalArgumentException("Invalid request guard type: " + agentId);
        }
        if (maxTokens == null || maxTokens < 1) {
            throw new IllegalArgumentException("Invalid request guard max_tokens: " + agentId);
        }
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("Invalid request guard max_attempts: " + agentId);
        }
    }
}
