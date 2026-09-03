package com.example.aichat.common.inputpolicy;

import com.example.aichat.common.inputpolicy.model.InputHistoryMessage;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.ResponseModeConfig;
import java.util.List;

/**
 * Converts a user request and optional history into provider-neutral LLM messages.
 * Implementations are selected by {@link InputPolicyRegistry} through profile configuration.
 */
public interface InputPolicy {

    /** Returns the stable configuration identifier of this policy. */
    String type();

    /** Builds and validates the messages sent to the LLM provider. */
    List<LlmMessage> buildMessages(
            AgentProfile profile,
            ResponseModeConfig responseMode,
            String rawMessage,
            List<InputHistoryMessage> history
    );
}
