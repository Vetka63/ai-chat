package com.example.aichat.common.llm.model;

import java.util.List;

/** Содержит независимые от провайдера параметры одного запроса на генерацию LLM. */
public record LlmCompletionRequest(
        String profileId,
        String operation,
        String model,
        String thinking,
        String reasoningEffort,
        Double temperature,
        Double topP,
        Integer maxTokens,
        Object stop,
        String responseFormat,
        List<LlmMessage> messages
) {
    public LlmCompletionRequest {
        messages = List.copyOf(messages);
    }
}
