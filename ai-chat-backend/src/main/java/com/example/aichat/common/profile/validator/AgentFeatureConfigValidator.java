package com.example.aichat.common.profile.validator;

import com.example.aichat.common.profile.model.AgentProfile;

/** Validates task-specific profile configuration during application startup. */
public interface AgentFeatureConfigValidator {
    String experienceType();

    void validate(AgentProfile profile);
}
