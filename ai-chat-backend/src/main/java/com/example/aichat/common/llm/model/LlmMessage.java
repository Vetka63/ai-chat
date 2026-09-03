package com.example.aichat.common.llm.model;

/** Представляет сообщение с ролью и содержимым в запросе или ответе LLM. */
public record LlmMessage(String role, String content) {
}
