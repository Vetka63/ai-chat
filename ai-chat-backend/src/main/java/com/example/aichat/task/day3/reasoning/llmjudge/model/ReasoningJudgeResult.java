package com.example.aichat.task.day3.reasoning.llmjudge.model;

import com.example.aichat.task.day3.reasoning.model.ExperimentMetrics;

import java.util.List;

/** Содержит проверенный результат автоматического сравнения вариантов рассуждения. */
public record ReasoningJudgeResult(
        String winnerStrategy,
        String winnerTitle,
        List<ReasoningJudgeEvaluation> evaluations,
        String explanation,
        ExperimentMetrics metrics,
        String model,
        String finishReason
) {
    public ReasoningJudgeResult {
        evaluations = List.copyOf(evaluations);
        metrics = metrics == null ? ExperimentMetrics.empty() : metrics;
    }
}
