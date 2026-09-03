package com.example.aichat.task.day4.temperature.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Описывает один именованный вариант температуры в эксперименте Дня 4. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TemperatureVariantConfig(
        String id,
        String title,
        String description,
        Double value
) {
    public void validate(String profileId) {
        if (id == null || !id.matches("^[a-z][a-z0-9-]{1,31}$")) {
            throw new IllegalArgumentException("Invalid temperature id: " + profileId);
        }
        requireText(title, "temperature title", profileId);
        requireText(description, "temperature description", profileId);
        if (value == null || value < 0 || value > 2) {
            throw new IllegalArgumentException("Invalid temperature value: " + profileId);
        }
    }

    private static void requireText(String value, String field, String profileId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field + ": " + profileId);
        }
    }
}
