package com.example.aichat.common.llm;

import com.example.aichat.common.llm.enums.LlmProvider;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmResult;

/** Выполняет запросы через один конкретный LLM-провайдер. */
public interface LlmProviderClient {
    LlmProvider provider();

    LlmResult complete(LlmCompletionRequest request);
}
