package com.example.aichat.task.day3.reasoning.llmjudge.model;

/** Candidate answer supplied to the Day 3 LLM judge. */
public record ReasoningJudgeCandidate(
        String strategy,
        String answer
) {
}
