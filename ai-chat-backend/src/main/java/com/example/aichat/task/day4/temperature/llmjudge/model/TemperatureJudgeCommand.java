package com.example.aichat.task.day4.temperature.llmjudge.model;

import java.util.List;

/** Содержит входные данные запуска автоматического судьи Дня 4. */
public record TemperatureJudgeCommand(
        String profileId,
        String task,
        List<TemperatureJudgeCandidate> candidates
) {
}
