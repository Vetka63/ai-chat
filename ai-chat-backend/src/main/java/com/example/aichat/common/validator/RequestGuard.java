package com.example.aichat.common.validator;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.RequestGuardConfig;
import com.example.aichat.common.llm.model.LlmMessage;

import java.util.List;

/** Validates an incoming request before the main LLM generation is executed. */
public interface RequestGuard {

    String type();

    void validate(
            AgentProfile profile,
            RequestGuardConfig config,
            List<LlmMessage> validatedMessages
    );
}
