package com.example.aichat.llm;

import com.example.aichat.agent.AgentProfile;
import com.example.aichat.agent.AgentProfile.ResponseModeConfig;

import java.util.List;

public interface LlmClient {
    LlmResult complete(
            AgentProfile profile,
            ResponseModeConfig responseMode,
            List<LlmMessage> messages
    );
}
