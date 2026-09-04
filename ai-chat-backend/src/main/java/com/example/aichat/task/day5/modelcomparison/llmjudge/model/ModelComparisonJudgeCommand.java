package com.example.aichat.task.day5.modelcomparison.llmjudge.model;

import java.util.List;

/** Описывает независимую оценку нескольких ответов на одну задачу Дня 5. */
public record ModelComparisonJudgeCommand(
        String profileId,
        String task,
        List<ModelComparisonJudgeCandidate> candidates
) {
    public ModelComparisonJudgeCommand {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
