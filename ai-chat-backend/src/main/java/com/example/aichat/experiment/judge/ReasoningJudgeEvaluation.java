package com.example.aichat.experiment.judge;

public record ReasoningJudgeEvaluation(
        String strategy,
        String title,
        ReasoningJudgeScores scores,
        double average,
        String strengths,
        String weaknesses
) {
}
