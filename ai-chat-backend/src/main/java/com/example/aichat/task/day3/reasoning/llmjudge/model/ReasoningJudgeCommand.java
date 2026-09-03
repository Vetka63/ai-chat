package com.example.aichat.task.day3.reasoning.llmjudge.model;

import java.util.List;

/** Contains all input required to compare Day 3 reasoning candidates. */
public record ReasoningJudgeCommand(
        String profileId,
        String task,
        List<ReasoningJudgeCandidate> candidates
) {
}
