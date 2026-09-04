package com.example.aichat.task.day5.modelcomparison.llmjudge.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Описывает один ответ-кандидат в HTTP-запросе к судье Дня 5. */
public record ModelComparisonJudgeCandidateRequest(
        @NotBlank(message = "Model id must not be blank")
        @Pattern(regexp = "^[a-z][a-z0-9-]{1,63}$", message = "Model id has an invalid format")
        String modelId,

        @NotBlank(message = "Candidate answer must not be blank")
        @Size(max = 50000, message = "Candidate answer must not exceed 50000 characters")
        String answer
) {
}
