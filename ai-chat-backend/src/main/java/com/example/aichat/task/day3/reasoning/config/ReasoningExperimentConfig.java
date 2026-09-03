package com.example.aichat.task.day3.reasoning.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Immutable configuration model for ReasoningExperiment settings. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReasoningExperimentConfig(
        ReasoningStrategyConfig direct,
        ReasoningStrategyConfig stepByStep,
        MetaPromptConfig metaPrompt,
        ExpertPanelConfig expertPanel,
        JudgeConfig judge
) {
    public void validate(String profileId) {
        if (direct == null || stepByStep == null || metaPrompt == null || expertPanel == null || judge == null) {
            throw new IllegalArgumentException("Incomplete reasoning feature config: " + profileId);
        }
        direct.validate(profileId, false);
        stepByStep.validate(profileId, true);
        metaPrompt.validate(profileId);
        expertPanel.validate(profileId);
        judge.validate(profileId);
    }
}
