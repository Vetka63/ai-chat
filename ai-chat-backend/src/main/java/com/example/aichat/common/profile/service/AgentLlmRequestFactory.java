package com.example.aichat.common.profile.service;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.ResponseModeConfig;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.llm.model.LlmRequestOverrides;
import org.springframework.util.StringUtils;

import java.util.List;

/** Преобразует настройки профиля и режима в независимый от провайдера запрос LLM. */
public final class AgentLlmRequestFactory {

    private AgentLlmRequestFactory() {
    }

    public static LlmCompletionRequest create(
            AgentProfile profile,
            ResponseModeConfig responseMode,
            List<LlmMessage> messages
    ) {
        return create(profile, responseMode, messages, LlmRequestOverrides.none());
    }

    /** Создаёт запрос, применяя непустые настройки конкретного вызова поверх профиля. */
    public static LlmCompletionRequest create(
            AgentProfile profile,
            ResponseModeConfig responseMode,
            List<LlmMessage> messages,
            LlmRequestOverrides overrides
    ) {
        var settings = profile.deepseek();
        var effectiveOverrides = overrides == null ? LlmRequestOverrides.none() : overrides;
        return new LlmCompletionRequest(
                profile.id(),
                responseMode.id(),
                StringUtils.hasText(effectiveOverrides.model())
                        ? effectiveOverrides.model().trim()
                        : settings.model(),
                effectiveOverrides.thinking() == null
                        ? settings.thinking()
                        : effectiveOverrides.thinking(),
                effectiveOverrides.reasoningEffort() == null
                        ? settings.reasoningEffort()
                        : effectiveOverrides.reasoningEffort(),
                effectiveOverrides.temperature() == null
                        ? settings.temperature()
                        : effectiveOverrides.temperature(),
                effectiveOverrides.topP() == null
                        ? settings.topP()
                        : effectiveOverrides.topP(),
                responseMode.maxTokens(),
                responseMode.stop(),
                responseMode.responseFormat(),
                messages
        );
    }
}
