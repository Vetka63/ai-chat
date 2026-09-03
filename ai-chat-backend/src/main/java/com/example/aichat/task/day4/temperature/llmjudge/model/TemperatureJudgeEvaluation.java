package com.example.aichat.task.day4.temperature.llmjudge.model;

/** Содержит оценку одного температурного варианта, сформированную LLM-судьёй. */
public record TemperatureJudgeEvaluation(
        String variantId,
        String title,
        double temperature,
        TemperatureJudgeScores scores,
        double average,
        String strengths,
        String weaknesses
) {
}
