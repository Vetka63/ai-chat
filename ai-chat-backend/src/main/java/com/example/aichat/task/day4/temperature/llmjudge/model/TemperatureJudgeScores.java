package com.example.aichat.task.day4.temperature.llmjudge.model;

/** Содержит проверенные оценки одного ответа по критериям Дня 4. */
public record TemperatureJudgeScores(int accuracy, int creativity, int instructionFollowing) {
    public double average() {
        return Math.round(((accuracy + creativity + instructionFollowing) / 3.0) * 10) / 10.0;
    }
}
