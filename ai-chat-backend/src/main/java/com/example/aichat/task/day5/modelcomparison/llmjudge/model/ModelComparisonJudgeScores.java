package com.example.aichat.task.day5.modelcomparison.llmjudge.model;

/** Хранит оценки содержания ответа по четырём критериям от 0 до 10. */
public record ModelComparisonJudgeScores(
        int accuracy,
        int instructionFollowing,
        int completeness,
        int clarity
) {
    public double average() {
        return (accuracy + instructionFollowing + completeness + clarity) / 4.0;
    }
}
