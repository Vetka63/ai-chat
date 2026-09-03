package com.example.aichat.task.chat.model;

/** Represents the ChatResultMeta concept within its owning domain. */
public record ChatResultMeta(
        String model,
        String finishReason,
        Integer maxTokens,
        String responseFormat,
        Object stop
) {
}
