package com.example.aichat.task.chat.model;

/** Содержит внутренние метаданные результата генерации чата. */
public record ChatResultMeta(
        String model,
        String finishReason,
        Integer maxTokens,
        String responseFormat,
        Object stop
) {
}
