package com.example.aichat.experiment;

import com.example.aichat.agent.AgentProfile;

public record ReasoningExperimentContext(
        AgentProfile profile,
        String task
) {
}
