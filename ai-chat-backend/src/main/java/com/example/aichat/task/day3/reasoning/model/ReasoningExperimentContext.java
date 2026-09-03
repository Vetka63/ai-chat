package com.example.aichat.task.day3.reasoning.model;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.task.day3.reasoning.config.ReasoningExperimentConfig;

/** Содержит неизменяемый контекст выполнения стратегий рассуждения Дня 3. */
public record ReasoningExperimentContext(
        AgentProfile profile,
        ReasoningExperimentConfig config,
        String task
) {
}
