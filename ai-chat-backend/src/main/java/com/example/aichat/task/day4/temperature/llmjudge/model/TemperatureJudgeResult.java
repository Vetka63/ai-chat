package com.example.aichat.task.day4.temperature.llmjudge.model;

import com.example.aichat.common.llm.model.AggregateLlmMetrics;

import java.util.List;

/** Содержит проверенный результат автоматического сравнения ответов Дня 4. */
public record TemperatureJudgeResult(
        String winnerVariantId,
        String winnerTitle,
        double winnerTemperature,
        List<TemperatureJudgeEvaluation> evaluations,
        int diversityScore,
        String diversityExplanation,
        String explanation,
        AggregateLlmMetrics metrics,
        String model,
        String finishReason
) {
    public TemperatureJudgeResult {
        evaluations = List.copyOf(evaluations);
        metrics = metrics == null ? AggregateLlmMetrics.empty() : metrics;
    }
}
