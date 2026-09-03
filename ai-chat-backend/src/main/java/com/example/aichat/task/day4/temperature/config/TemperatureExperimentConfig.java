package com.example.aichat.task.day4.temperature.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.HashSet;
import java.util.List;

/** Содержит и проверяет конфигурацию температурного эксперимента Дня 4. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TemperatureExperimentConfig(
        List<TemperatureVariantConfig> temperatures,
        Integer maxTokens
) {
    public TemperatureExperimentConfig {
        temperatures = temperatures == null ? List.of() : List.copyOf(temperatures);
    }

    public void validate(String profileId) {
        if (temperatures.size() < 2 || temperatures.size() > 10) {
            throw new IllegalArgumentException("From two to ten temperatures are required: " + profileId);
        }
        if (maxTokens == null || maxTokens < 1 || maxTokens > 8_000) {
            throw new IllegalArgumentException("Invalid temperature max_tokens: " + profileId);
        }
        var ids = new HashSet<String>();
        var values = new HashSet<Double>();
        for (var temperature : temperatures) {
            if (temperature == null) {
                throw new IllegalArgumentException("Temperature must not be null: " + profileId);
            }
            temperature.validate(profileId);
            if (!ids.add(temperature.id())) {
                throw new IllegalArgumentException("Duplicate temperature id: " + temperature.id());
            }
            if (!values.add(temperature.value())) {
                throw new IllegalArgumentException("Duplicate temperature value: " + temperature.value());
            }
        }
    }
}
