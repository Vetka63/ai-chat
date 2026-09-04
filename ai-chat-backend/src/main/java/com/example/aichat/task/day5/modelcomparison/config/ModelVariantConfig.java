package com.example.aichat.task.day5.modelcomparison.config;

import com.example.aichat.common.llm.enums.LlmProvider;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Описывает одну модель, участвующую в сравнении Дня 5. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelVariantConfig(
        String id,
        String title,
        String description,
        LlmProvider provider,
        String model,
        String modelUrl,
        String pricingUrl,
        ModelPricingConfig pricing
) {
    public void validate(String profileId) {
        requireText(id, "model id", profileId);
        requireText(title, "model title", profileId);
        requireText(description, "model description", profileId);
        requireText(model, "model API id", profileId);
        requireHttpsUrl(modelUrl, "model URL", profileId);
        requireHttpsUrl(pricingUrl, "pricing URL", profileId);
        if (provider == null) {
            throw new IllegalArgumentException("Missing model provider: " + profileId);
        }
        if (!id.matches("^[a-z][a-z0-9-]{1,31}$")) {
            throw new IllegalArgumentException("Invalid model variant id: " + id);
        }
        if (pricing == null) {
            throw new IllegalArgumentException("Missing model pricing: " + id);
        }
        pricing.validate(id);
    }

    private static void requireText(String value, String field, String profileId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field + ": " + profileId);
        }
    }

    private static void requireHttpsUrl(String value, String field, String profileId) {
        requireText(value, field, profileId);
        if (!value.startsWith("https://")) {
            throw new IllegalArgumentException("Invalid " + field + ": " + profileId);
        }
    }
}
