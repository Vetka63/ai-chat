package com.example.aichat.llm;

public record LlmUsage(
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long promptCacheHitTokens,
        long promptCacheMissTokens,
        long reasoningTokens
) {
    public static LlmUsage empty() {
        return new LlmUsage(0, 0, 0, 0, 0, 0);
    }
}
