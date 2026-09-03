package com.example.aichat.config;

import com.example.aichat.config.enums.Mode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/** Immutable configuration model for Chat settings. */
@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(
        Mode mode,
        String baseUrl,
        String apiKey,
        String model,
        String agentProfilesPattern,
        String defaultAgentId,
        boolean logPayloads,
        Pricing pricing,
        List<String> allowedOrigins
) {
    public ChatProperties {
        pricing = pricing == null ? Pricing.free() : pricing;
    }

    public record Pricing(
            BigDecimal promptCacheMissPerMillionUsd,
            BigDecimal promptCacheHitPerMillionUsd,
            BigDecimal outputPerMillionUsd
    ) {
        public Pricing {
            promptCacheMissPerMillionUsd = nonNegative(promptCacheMissPerMillionUsd);
            promptCacheHitPerMillionUsd = nonNegative(promptCacheHitPerMillionUsd);
            outputPerMillionUsd = nonNegative(outputPerMillionUsd);
        }

        public static Pricing free() {
            return new Pricing(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        public boolean configured() {
            return promptCacheMissPerMillionUsd.signum() > 0
                    || promptCacheHitPerMillionUsd.signum() > 0
                    || outputPerMillionUsd.signum() > 0;
        }

        private static BigDecimal nonNegative(BigDecimal value) {
            if (value == null) {
                return BigDecimal.ZERO;
            }
            if (value.signum() < 0) {
                throw new IllegalArgumentException("LLM token prices must not be negative");
            }
            return value;
        }
    }
}
