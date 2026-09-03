package com.example.aichat.common.profile.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable server-side agent profile loaded from YAML configuration. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentProfile(
        String id,
        String name,
        String description,
        int version,
        boolean enabled,
        String experienceType,
        String systemPrompt,
        InputPolicyConfig inputPolicy,
        RequestGuardConfig requestGuard,
        DeepSeekConfig deepseek,
        Map<String, Object> featureConfig,
        String defaultResponseMode,
        List<ResponseModeConfig> responseModes
) {
    public AgentProfile {
        requireText(id, "id");
        if (!id.matches("^[a-z][a-z0-9-]{1,63}$")) {
            throw new IllegalArgumentException("Agent id has an invalid format: " + id);
        }
        requireText(name, "name");
        requireText(description, "description");
        experienceType = experienceType == null || experienceType.isBlank()
                ? "chat"
                : experienceType.trim();
        if (!experienceType.matches("^[a-z][a-z0-9-]{1,63}$")) {
            throw new IllegalArgumentException("Experience type has an invalid format: " + id);
        }
        requireText(systemPrompt, "system_prompt");
        requireText(defaultResponseMode, "default_response_mode");
        if (version < 1) {
            throw new IllegalArgumentException("Agent version must be positive: " + id);
        }
        if (inputPolicy == null || deepseek == null) {
            throw new IllegalArgumentException("Agent policies must be configured: " + id);
        }
        featureConfig = featureConfig == null ? Map.of() : Map.copyOf(featureConfig);
        responseModes = responseModes == null ? List.of() : List.copyOf(responseModes);
        inputPolicy.validate(id);
        if (requestGuard != null) {
            requestGuard.validate(id);
        }
        deepseek.validate(id);
        validateResponseModes(id, defaultResponseMode, responseModes);
    }

    public ResponseModeConfig responseMode(String responseModeId) {
        return responseModes.stream()
                .filter(mode -> mode.id().equals(responseModeId))
                .findFirst()
                .orElse(null);
    }

    private static void validateResponseModes(
            String agentId,
            String defaultMode,
            List<ResponseModeConfig> modes
    ) {
        if (modes.isEmpty()) {
            throw new IllegalArgumentException("Agent must define response modes: " + agentId);
        }
        Set<String> ids = new HashSet<>();
        for (var mode : modes) {
            if (mode == null) {
                throw new IllegalArgumentException("Response mode must not be null: " + agentId);
            }
            mode.validate(agentId);
            if (!ids.add(mode.id())) {
                throw new IllegalArgumentException("Duplicate response mode: " + mode.id());
            }
        }
        if (!ids.contains(defaultMode)) {
            throw new IllegalArgumentException("Default response mode does not exist: " + agentId);
        }
    }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Agent field must not be blank: " + field);
        }
    }
}
