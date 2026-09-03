package com.example.aichat.task.day4.temperature.llmjudge.model;

/** Содержит идентификатор температурного варианта и его ответ для судьи. */
public record TemperatureJudgeCandidate(String variantId, String answer) {
}
