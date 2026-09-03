package com.example.aichat.task.day3.reasoning.model;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.task.day3.reasoning.config.ReasoningExperimentConfig;

/** Immutable execution context shared with Day 3 reasoning strategies. */
public record ReasoningExperimentContext(
        AgentProfile profile,
        ReasoningExperimentConfig config,
        String task
) {
}
