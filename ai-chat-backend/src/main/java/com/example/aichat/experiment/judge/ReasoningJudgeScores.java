package com.example.aichat.experiment.judge;

public record ReasoningJudgeScores(
        int correctness,
        int clarity,
        int completeness,
        int efficiency,
        int edgeCases
) {
    double average() {
        return Math.round(
                ((correctness + clarity + completeness + efficiency + edgeCases) / 5.0) * 10
        ) / 10.0;
    }
}
