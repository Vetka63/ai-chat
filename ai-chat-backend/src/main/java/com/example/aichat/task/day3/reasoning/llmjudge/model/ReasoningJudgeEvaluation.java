package com.example.aichat.task.day3.reasoning.llmjudge.model;

/** Содержит оценку одного кандидата, сформированную LLM-судьёй Дня 3. */
public record ReasoningJudgeEvaluation(
        String strategy,
        String title,
        ReasoningJudgeScores scores,
        double average,
        String strengths,
        String weaknesses
) {
}
