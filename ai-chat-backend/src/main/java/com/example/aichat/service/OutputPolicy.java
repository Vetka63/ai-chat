package com.example.aichat.service;

import com.example.aichat.llm.LlmResult;

public interface OutputPolicy {
    String type();

    OutputPolicyResult apply(LlmResult result);

    record OutputPolicyResult(String reply, Object structuredReply) {
    }
}
