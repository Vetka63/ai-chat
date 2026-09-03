package com.example.aichat.task.day3.reasoning.llmjudge.model;

/** Validated score breakdown returned by the Day 3 LLM judge. */
public record ReasoningJudgeScores(
        int correctness,
        int clarity,
        int completeness,
        int efficiency,
        int edgeCases
) {
    public double average() {
        return Math.round(
                ((correctness + clarity + completeness + efficiency + edgeCases) / 5.0) * 10
        ) / 10.0;
    }
}
