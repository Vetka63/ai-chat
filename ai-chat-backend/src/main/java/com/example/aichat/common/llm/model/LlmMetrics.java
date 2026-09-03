package com.example.aichat.common.llm.model;

import java.math.BigDecimal;

/** Содержит агрегированные метрики выполнения одного вызова LLM. */
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
