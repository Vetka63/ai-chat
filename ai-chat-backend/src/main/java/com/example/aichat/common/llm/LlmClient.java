package com.example.aichat.common.llm;

import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmResult;

/** Defines or implements the provider boundary represented by LlmClient. */
public interface LlmClient {
    LlmResult complete(LlmCompletionRequest request);
}
