package com.example.aichat.task.day3.reasoning.llmjudge.model;

/** Evaluation of one candidate returned by the Day 3 LLM judge. */
public record ReasoningJudgeEvaluation(
        String strategy,
        String title,
        ReasoningJudgeScores scores,
        double average,
        String strengths,
        String weaknesses
) {
}
