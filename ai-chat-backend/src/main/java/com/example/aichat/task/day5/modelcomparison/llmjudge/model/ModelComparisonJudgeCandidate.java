package com.example.aichat.task.day5.modelcomparison.llmjudge.model;

/** Передаёт судье идентификатор варианта и его текстовый ответ. */
public record ModelComparisonJudgeCandidate(String modelId, String answer) {
}
