package com.example.aichat.task.chat.controller.dto;

import com.example.aichat.common.enums.HistoryRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Immutable message model used at the History boundary. */
public record HistoryMessage(
        @NotNull(message = "History role is required")
        HistoryRole role,

        @NotBlank(message = "History message must not be blank")
        @Size(max = 100_000, message = "History message is too long")
        String content
) {
}
