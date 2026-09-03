package com.example.aichat.task.day4.temperature.config;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.validator.AgentFeatureConfigValidator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Читает типизированную конфигурацию Дня 4 из общего YAML-профиля агента. */
@Component
public class TemperatureConfigProvider implements AgentFeatureConfigValidator {

    public static final String EXPERIENCE_TYPE = "temperature-experiment";
    private final ObjectMapper objectMapper;

    public TemperatureConfigProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public TemperatureExperimentConfig get(AgentProfile profile) {
        if (!EXPERIENCE_TYPE.equals(profile.experienceType())) {
            throw new IllegalArgumentException(
                    "Profile does not contain Day 4 temperature configuration: " + profile.id()
            );
        }
        try {
            var config = objectMapper.convertValue(
                    profile.featureConfig(),
                    TemperatureExperimentConfig.class
            );
            config.validate(profile.id());
            return config;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid Day 4 temperature configuration: " + profile.id(),
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
