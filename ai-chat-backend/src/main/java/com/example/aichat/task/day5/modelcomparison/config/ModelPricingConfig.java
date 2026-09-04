package com.example.aichat.task.day5.modelcomparison.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;

/** Описывает фиксированный тариф модели в долларах за миллион токенов. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelPricingConfig(
        BigDecimal promptCacheMissPerMillionUsd,
        BigDecimal promptCacheHitPerMillionUsd,
        BigDecimal outputPerMillionUsd,
        String label
) {
    public void validate(String variantId) {
        requireNonNegative(promptCacheMissPerMillionUsd, "prompt cache miss", variantId);
        requireNonNegative(promptCacheHitPerMillionUsd, "prompt cache hit", variantId);
        requireNonNegative(outputPerMillionUsd, "output", variantId);
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Missing pricing label: " + variantId);
        }
    }

    private static void requireNonNegative(BigDecimal value, String field, String variantId) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("Invalid " + field + " price: " + variantId);
        }
    }
}
