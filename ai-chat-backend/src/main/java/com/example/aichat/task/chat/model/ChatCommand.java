package com.example.aichat.task.chat.model;

import com.example.aichat.common.inputpolicy.model.InputHistoryMessage;

import java.util.List;

/** Immutable application command for the Chat workflow. */
public record ChatCommand(
        String message,
        String profileId,
        String responseMode,
        List<InputHistoryMessage> history
) {
    public ChatCommand {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
