package com.example.aichat.llm;

public record LlmResult(
        String content,
        String model,
        String finishReason,
        LlmMetrics metrics
) {
    public LlmResult {
        metrics = metrics == null ? LlmMetrics.empty() : metrics;
    }

    public LlmResult(String content, String model, String finishReason) {
        this(content, model, finishReason, LlmMetrics.empty());
    }
}
