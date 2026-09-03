package com.example.aichat.common.llm.model;

/**
 * Задаёт необязательные параметры одного LLM-вызова поверх настроек профиля.
 * Значение {@code null} означает, что нужно сохранить параметр из профиля.
 */
public record LlmRequestOverrides(
        String model,
        String thinking,
        String reasoningEffort,
        Double temperature,
        Double topP
) {
    public static LlmRequestOverrides none() {
        return new LlmRequestOverrides(null, null, null, null, null);
    }

    public static LlmRequestOverrides temperature(double value) {
        return new LlmRequestOverrides(null, null, null, value, null);
    }

    public static LlmRequestOverrides model(
            String model,
            String thinking,
            String reasoningEffort
    ) {
        return new LlmRequestOverrides(model, thinking, reasoningEffort, null, null);
    }
}
