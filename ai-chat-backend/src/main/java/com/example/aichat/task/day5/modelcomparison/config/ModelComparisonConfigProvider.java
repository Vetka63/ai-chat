package com.example.aichat.task.day5.modelcomparison.config;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.validator.AgentFeatureConfigValidator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Читает типизированную конфигурацию сравнения моделей из YAML-профиля Дня 5. */
@Component
public class ModelComparisonConfigProvider implements AgentFeatureConfigValidator {

    public static final String EXPERIENCE_TYPE = "model-comparison";
    private final ObjectMapper objectMapper;

    public ModelComparisonConfigProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public ModelComparisonConfig get(AgentProfile profile) {
        if (!EXPERIENCE_TYPE.equals(profile.experienceType())) {
            throw new IllegalArgumentException(
                    "Profile does not contain Day 5 model comparison configuration: " + profile.id()
            );
        }
        try {
            var config = objectMapper.convertValue(profile.featureConfig(), ModelComparisonConfig.class);
            config.validate(profile.id());
            return config;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid Day 5 model comparison configuration: " + profile.id(),
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
