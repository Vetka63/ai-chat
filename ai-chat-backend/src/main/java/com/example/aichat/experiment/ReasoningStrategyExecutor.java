package com.example.aichat.experiment;

import com.example.aichat.enums.ReasoningStrategy;

public interface ReasoningStrategyExecutor {
    ReasoningStrategy strategy();

    ReasoningStrategyResult execute(ReasoningExperimentContext context);
}
