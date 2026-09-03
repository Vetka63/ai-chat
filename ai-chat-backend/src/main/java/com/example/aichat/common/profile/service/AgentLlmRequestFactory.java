package com.example.aichat.common.profile.service;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.ResponseModeConfig;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmMessage;

import java.util.List;

/** Creates provider-neutral request objects for the responsibility represented by AgentLlmRequestFactory. */
public final class AgentLlmRequestFactory {

    private AgentLlmRequestFactory() {
    }

    public static LlmCompletionRequest create(
            AgentProfile profile,
            ResponseModeConfig responseMode,
            List<LlmMessage> messages
    ) {
        var settings = profile.deepseek();
        return new LlmCompletionRequest(
                profile.id(),
                responseMode.id(),
                settings.model(),
                settings.thinking(),
                settings.reasoningEffort(),
                settings.temperature(),
                settings.topP(),
                responseMode.maxTokens(),
                responseMode.stop(),
                responseMode.responseFormat(),
                messages
        );
    }
}
