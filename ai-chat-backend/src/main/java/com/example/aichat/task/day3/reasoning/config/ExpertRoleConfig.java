package com.example.aichat.task.day3.reasoning.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Immutable configuration model for ExpertRole settings. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExpertRoleConfig(String id, String name, String instruction) {
    public void validate(String profileId) {
        MetaPromptConfig.requireText(id, "expert role id", profileId);
        MetaPromptConfig.requireText(name, "expert role name", profileId);
        MetaPromptConfig.requireText(instruction, "expert role instruction", profileId);
        if (!id.matches("^[a-z][a-z0-9-]{1,19}$")) {
            throw new IllegalArgumentException("Invalid expert role id: " + profileId);
        }
    }
}
