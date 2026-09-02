package com.example.aichat.llm;

import java.math.BigDecimal;

public record LlmMetrics(
        long durationMs,
        LlmUsage usage,
        BigDecimal estimatedCostUsd
) {
    public LlmMetrics {
        durationMs = Math.max(0, durationMs);
        usage = usage == null ? LlmUsage.empty() : usage;
    }

    public static LlmMetrics empty() {
        return new LlmMetrics(0, LlmUsage.empty(), null);
    }
}
