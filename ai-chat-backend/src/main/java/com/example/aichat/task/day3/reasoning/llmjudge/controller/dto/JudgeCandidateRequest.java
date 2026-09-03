package com.example.aichat.task.day3.reasoning.llmjudge.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Описывает один кандидатский ответ, передаваемый LLM-судье. */
public record JudgeCandidateRequest(
        @NotBlank(message = "Candidate strategy must not be blank")
        @Pattern(
                regexp = "^(direct|step-by-step|meta-prompt|expert-panel)$",
                message = "Candidate strategy has an invalid format"
        )
        String strategy,

        @NotBlank(message = "Candidate answer must not be blank")
        @Size(max = 20000, message = "Candidate answer must not exceed 20000 characters")
        String answer
) {
}
