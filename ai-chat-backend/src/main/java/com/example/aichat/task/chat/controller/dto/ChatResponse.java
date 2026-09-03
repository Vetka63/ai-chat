package com.example.aichat.task.chat.controller.dto;

/** Описывает HTTP-ответ чата, включая структурированный результат и метаданные. */
public record ChatResponse(
        String reply,
        Object structuredReply,
        String source,
        String profileId,
        String responseMode,
        ChatMeta meta
) {
}
