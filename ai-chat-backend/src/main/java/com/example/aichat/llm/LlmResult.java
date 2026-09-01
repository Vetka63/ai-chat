package com.example.aichat.llm;

public record LlmResult(
        String content,
        String model,
        String finishReason
) {
}
