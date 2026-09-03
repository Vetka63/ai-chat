package com.example.aichat.task.chat.controller.dto;

/** Содержит метаданные выполнения запроса чата и применённых ограничений ответа. */
public record ChatMeta(
        String model,
        String finishReason,
        Integer maxTokens,
        String responseFormat,
        Object stop
) {
}
