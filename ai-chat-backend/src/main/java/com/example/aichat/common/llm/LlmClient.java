package com.example.aichat.common.llm;

import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmResult;

/** Задаёт независимый от провайдера контракт выполнения запроса к LLM. */
public interface LlmClient {
    LlmResult complete(LlmCompletionRequest request);
}
