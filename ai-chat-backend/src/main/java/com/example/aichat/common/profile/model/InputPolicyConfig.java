package com.example.aichat.common.profile.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Set;

/** Immutable configuration model for InputPolicy settings. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record InputPolicyConfig(
        String type,
        int maxMessageLength,
        int maxHistoryMessages,
        List<String> allowedRoles
) {
    private static final Set<String> ALLOWED_HISTORY_ROLES = Set.of("user", "assistant");

    public InputPolicyConfig {
        type = type == null || type.isBlank() ? "standard" : type.trim();
        allowedRoles = allowedRoles == null ? List.of() : List.copyOf(allowedRoles);
    }


    public InputPolicyConfig(
            int maxMessageLength,
            int maxHistoryMessages,
            List<String> allowedRoles
    ) {
        this("standard", maxMessageLength, maxHistoryMessages, allowedRoles);
    }

    public void validate(String agentId) {
        if (!type.matches("[a-z0-9][a-z0-9-]*")
                || maxMessageLength < 1
                || maxHistoryMessages < 0) {
            throw new IllegalArgumentException("Invalid input policy: " + agentId);
        }
        if (allowedRoles.isEmpty() || !ALLOWED_HISTORY_ROLES.containsAll(allowedRoles)) {
            throw new IllegalArgumentException("Invalid history roles: " + agentId);
        }
    }
}
