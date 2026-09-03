package com.example.aichat.task.day4.temperature.model;

import com.example.aichat.common.llm.model.AggregateLlmMetrics;

import java.util.List;

/** Представляет полный результат сравнения одной задачи при разных температурах. */
public record TemperatureExperiment(
        String experimentId,
        String profileId,
        String task,
        List<TemperatureVariantResult> results,
        AggregateLlmMetrics metrics
) {
    public TemperatureExperiment {
        results = List.copyOf(results);
        metrics = metrics == null ? AggregateLlmMetrics.empty() : metrics;
    }
}
