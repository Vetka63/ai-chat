package com.example.aichat.task.day3.reasoning.llmjudge.model;

import java.util.List;

/** Содержит все входные данные для сравнения вариантов рассуждения Дня 3. */
public record ReasoningJudgeCommand(
        String profileId,
        String task,
        List<ReasoningJudgeCandidate> candidates
) {
}
