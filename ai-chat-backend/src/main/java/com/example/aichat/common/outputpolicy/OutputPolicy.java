package com.example.aichat.common.outputpolicy;

import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.outputpolicy.model.OutputPolicyResult;

/** Applies the configurable processing rule represented by OutputPolicy. */
public interface OutputPolicy {
    String type();

    OutputPolicyResult apply(LlmResult result);

    OutputPolicyResult fallback();

    default boolean supportsStructuredRetry() {
        return false;
    }

    default String retryConstraint() {
        return "";
    }
}
