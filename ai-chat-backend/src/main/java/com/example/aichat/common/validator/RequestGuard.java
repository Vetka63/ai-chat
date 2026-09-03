package com.example.aichat.common.validator;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.RequestGuardConfig;
import com.example.aichat.common.llm.model.LlmMessage;

import java.util.List;

/** Проверяет пользовательский запрос до запуска основной генерации LLM. */
public interface RequestGuard {

    String type();

    void validate(
            AgentProfile profile,
            RequestGuardConfig config,
            List<LlmMessage> validatedMessages
    );
}
