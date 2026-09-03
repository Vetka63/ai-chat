package com.example.aichat.task.day3.reasoning.model;

import java.util.List;

/** Aggregate result of a Day 3 multi-strategy reasoning experiment. */
public record ReasoningExperiment(
        String experimentId,
        String profileId,
        String task,
        List<ReasoningStrategyResult> results,
        ExperimentMetrics metrics
) {
}
