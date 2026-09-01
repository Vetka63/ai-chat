package com.example.aichat.service;

import com.example.aichat.agent.AgentProfile;
import com.example.aichat.agent.AgentProfile.RequestGuardConfig;
import com.example.aichat.llm.LlmMessage;

import java.util.List;

public interface RequestGuard {

    String type();

    void validate(
            AgentProfile profile,
            RequestGuardConfig config,
            List<LlmMessage> validatedMessages
    );
}
