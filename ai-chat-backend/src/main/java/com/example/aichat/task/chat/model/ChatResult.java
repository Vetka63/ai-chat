package com.example.aichat.task.chat.model;

/** Immutable application result produced by the Chat workflow. */
public record ChatResult(
        String reply,
        Object structuredReply,
        String source,
        String profileId,
        String responseMode,
        ChatResultMeta meta
) {
}
