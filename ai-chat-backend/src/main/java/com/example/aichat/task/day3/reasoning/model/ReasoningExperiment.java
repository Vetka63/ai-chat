package com.example.aichat.task.day3.reasoning.model;

import java.util.List;

/** Содержит совокупный результат эксперимента Дня 3 по нескольким стратегиям. */
public record ReasoningExperiment(
        String experimentId,
        String profileId,
        String task,
        List<ReasoningStrategyResult> results,
        ExperimentMetrics metrics
) {
}
