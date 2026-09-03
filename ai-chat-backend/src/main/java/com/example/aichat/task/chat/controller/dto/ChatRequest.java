package com.example.aichat.task.chat.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** HTTP request DTO for the Chat operation. */
public record ChatRequest(
        @NotBlank(message = "Message must not be blank")
        @Size(max = 100_000, message = "Message must not exceed 100000 characters")
        String message,

        @Pattern(
                regexp = "^[a-z][a-z0-9-]{1,63}$",
                message = "Profile id has an invalid format"
        )
        String profileId,

        @Pattern(
                regexp = "^[a-z][a-z0-9-]{1,31}$",
                message = "Response mode has an invalid format"
        )
        String responseMode,

        @Size(max = 200, message = "History must not exceed 200 messages")
        List<@Valid HistoryMessage> history
) {
    public ChatRequest {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
