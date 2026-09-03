package com.example.aichat.task.day3.reasoning.config;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.validator.AgentFeatureConfigValidator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Читает и проверяет типизированную конфигурацию эксперимента Дня 3 из профиля. */
@Component
public class ReasoningConfigProvider implements AgentFeatureConfigValidator {

    public static final String EXPERIENCE_TYPE = "reasoning-experiment";

    private final ObjectMapper objectMapper;

    public ReasoningConfigProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public ReasoningExperimentConfig get(AgentProfile profile) {
        if (!EXPERIENCE_TYPE.equals(profile.experienceType())) {
            throw new IllegalArgumentException(
                    "Profile does not contain Day 3 reasoning configuration: " + profile.id()
            );
        }
        try {
            var config = objectMapper.convertValue(
                    profile.featureConfig(),
                    ReasoningExperimentConfig.class
            );
            config.validate(profile.id());
            return config;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid Day 3 reasoning configuration: " + profile.id(),
                    exception
            );
        }
    }

    @Override
    public String experienceType() {
        return EXPERIENCE_TYPE;
    }

    @Override
    public void validate(AgentProfile profile) {
        get(profile);
    }
}
