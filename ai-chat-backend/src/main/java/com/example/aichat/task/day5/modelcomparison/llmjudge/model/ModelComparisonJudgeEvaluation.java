package com.example.aichat.task.day5.modelcomparison.llmjudge.model;

/** Сопоставляет варианту оценки судьи, средний балл и краткий разбор. */
public record ModelComparisonJudgeEvaluation(
        String modelId,
        String title,
        ModelComparisonJudgeScores scores,
        double average,
        String strengths,
        String weaknesses
) {
}
