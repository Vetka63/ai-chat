package com.example.aichat.task.day3.reasoning.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Описывает HTTP-запрос на запуск нескольких стратегий рассуждения. */
public record ReasoningExperimentRequest(
        @NotBlank(message = "Task must not be blank")
        @Size(max = 10000, message = "Task must not exceed 10000 characters")
        String task,

        @Pattern(
                regexp = "^[a-z][a-z0-9-]{1,63}$",
                message = "Profile id has an invalid format"
        )
        String profileId,

        @Size(max = 4, message = "No more than four reasoning strategies are allowed")
        List<
                @Pattern(
                        regexp = "^(direct|step-by-step|meta-prompt|expert-panel)$",
                        message = "Reasoning strategy has an invalid format"
                ) String
                > strategies
) {
    public ReasoningExperimentRequest {
        strategies = strategies == null ? List.of() : List.copyOf(strategies);
    }
}
