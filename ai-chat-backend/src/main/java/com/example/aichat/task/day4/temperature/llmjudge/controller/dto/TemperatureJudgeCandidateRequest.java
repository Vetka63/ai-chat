package com.example.aichat.task.day4.temperature.llmjudge.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Описывает один ответ температурного эксперимента, передаваемый судье. */
public record TemperatureJudgeCandidateRequest(
        @NotBlank(message = "Candidate variant id must not be blank")
        @Pattern(regexp = "^[a-z][a-z0-9-]{1,31}$", message = "Candidate variant id has an invalid format")
        String variantId,

        @NotBlank(message = "Candidate answer must not be blank")
        @Size(max = 20000, message = "Candidate answer must not exceed 20000 characters")
        String answer
) {
}
