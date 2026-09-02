package com.example.aichat.experiment.judge;

import com.example.aichat.experiment.ExperimentMetrics;

import java.util.List;

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
