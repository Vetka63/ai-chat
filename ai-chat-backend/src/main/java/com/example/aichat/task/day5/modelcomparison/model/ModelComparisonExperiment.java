package com.example.aichat.task.day5.modelcomparison.model;

import com.example.aichat.common.llm.model.AggregateLlmMetrics;

import java.util.List;

/** Представляет полный результат сравнения одной задачи на трёх моделях. */
public record ModelComparisonExperiment(
        String experimentId,
        String profileId,
        String task,
        List<ModelVariantResult> results,
        AggregateLlmMetrics metrics
) {
    public ModelComparisonExperiment {
        results = List.copyOf(results);
        metrics = metrics == null ? AggregateLlmMetrics.empty() : metrics;
    }
}
