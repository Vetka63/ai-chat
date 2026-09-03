package com.example.aichat.task.chat.controller.dto;

/** Represents the ChatMeta concept within its owning domain. */
public record ChatMeta(
        String model,
        String finishReason,
        Integer maxTokens,
        String responseFormat,
        Object stop
) {
}
