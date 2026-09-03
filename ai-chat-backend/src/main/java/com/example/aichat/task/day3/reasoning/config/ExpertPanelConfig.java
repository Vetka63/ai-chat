package com.example.aichat.task.day3.reasoning.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable configuration model for ExpertPanel settings. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExpertPanelConfig(
        String commonInstruction,
        List<ExpertRoleConfig> roles,
        String synthesisInstruction,
        Integer expertMaxTokens,
        Integer synthesisMaxTokens
) {
    public ExpertPanelConfig {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public void validate(String profileId) {
        MetaPromptConfig.requireText(commonInstruction, "expert common_instruction", profileId);
        MetaPromptConfig.requireText(synthesisInstruction, "expert synthesis_instruction", profileId);
        if (roles.size() < 2 || roles.size() > 6) {
            throw new IllegalArgumentException("Expert panel must contain from two to six roles: " + profileId);
        }
        Set<String> ids = new HashSet<>();
        for (var role : roles) {
            if (role == null) {
                throw new IllegalArgumentException("Expert role must not be null: " + profileId);
            }
            role.validate(profileId);
            if (!ids.add(role.id())) {
                throw new IllegalArgumentException("Duplicate expert role: " + role.id());
            }
        }
        MetaPromptConfig.validateTokenLimit(expertMaxTokens, "expert_max_tokens", profileId);
        MetaPromptConfig.validateTokenLimit(synthesisMaxTokens, "synthesis_max_tokens", profileId);
    }
}
