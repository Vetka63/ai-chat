package com.example.aichat.common.profile.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** Immutable configuration model for ResponseMode settings. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ResponseModeConfig(
        String id,
        String name,
        String description,
        String instruction,
        String outputPolicy,
        Integer maxTokens,
        Object stop,
        String responseFormat
) {
    public void validate(String agentId) {
        AgentProfile.requireText(id, "response_modes.id");
        AgentProfile.requireText(name, "response_modes.name");
        AgentProfile.requireText(description, "response_modes.description");
        AgentProfile.requireText(outputPolicy, "response_modes.output_policy");
        if (!id.matches("^[a-z][a-z0-9-]{1,31}$")) {
            throw new IllegalArgumentException("Invalid response mode id: " + agentId);
        }
        if (maxTokens != null && maxTokens < 1) {
            throw new IllegalArgumentException("Invalid max_tokens: " + id);
        }
        if (responseFormat != null && !responseFormat.equals("json_object")) {
            throw new IllegalArgumentException("Invalid response_format: " + id);
        }
        validateStop(id, stop);
    }

    private static void validateStop(String modeId, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return;
        }
        if (value instanceof List<?> values
                && !values.isEmpty()
                && values.size() <= 4
                && values.stream().allMatch(item -> item instanceof String text && !text.isBlank())) {
            return;
        }
        throw new IllegalArgumentException("Invalid stop sequence: " + modeId);
    }
}
