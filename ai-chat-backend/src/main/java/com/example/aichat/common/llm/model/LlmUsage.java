package com.example.aichat.common.llm.model;

/** Содержит статистику использования токенов, возвращённую провайдером LLM. */
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
