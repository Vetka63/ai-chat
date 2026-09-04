package com.example.aichat.task.day5.modelcomparison.llmjudge.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Описывает HTTP-запрос на автоматическое сравнение ответов Дня 5. */
public record ModelComparisonJudgeRequest(
        @NotBlank(message = "Task must not be blank")
        @Size(max = 10000, message = "Task must not exceed 10000 characters")
        String task,

        @NotBlank(message = "Profile id must not be blank")
        @Pattern(regexp = "^[a-z][a-z0-9-]{1,63}$", message = "Profile id has an invalid format")
        String profileId,

        @NotNull(message = "Candidate answers are required")
        @Valid
        @Size(min = 2, max = 3, message = "From two to three candidate answers are required")
        List<ModelComparisonJudgeCandidateRequest> candidates
) {
}
