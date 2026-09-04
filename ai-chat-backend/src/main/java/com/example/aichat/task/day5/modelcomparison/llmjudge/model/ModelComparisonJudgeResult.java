package com.example.aichat.task.day5.modelcomparison.llmjudge.model;

import com.example.aichat.common.llm.model.AggregateLlmMetrics;

import java.util.List;

/** Возвращает проверенное решение судьи, победителя и стоимость оценки. */
public record ModelComparisonJudgeResult(
        String winnerModelId,
        String winnerTitle,
        List<ModelComparisonJudgeEvaluation> evaluations,
        String summary,
        AggregateLlmMetrics metrics,
        String model,
        String finishReason
) {
    public ModelComparisonJudgeResult {
        evaluations = List.copyOf(evaluations);
        metrics = metrics == null ? AggregateLlmMetrics.empty() : metrics;
    }
}
