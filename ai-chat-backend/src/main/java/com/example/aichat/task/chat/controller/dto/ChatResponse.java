package com.example.aichat.task.chat.controller.dto;

/** HTTP response DTO for the Chat operation. */
public record ChatResponse(
        String reply,
        Object structuredReply,
        String source,
        String profileId,
        String responseMode,
        ChatMeta meta
) {
}
