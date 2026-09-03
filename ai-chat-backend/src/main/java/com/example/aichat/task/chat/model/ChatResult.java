package com.example.aichat.task.chat.model;

/** Содержит результат прикладного сценария чата до преобразования в HTTP DTO. */
public record ChatResult(
        String reply,
        Object structuredReply,
        String source,
        String profileId,
        String responseMode,
        ChatResultMeta meta
) {
}
