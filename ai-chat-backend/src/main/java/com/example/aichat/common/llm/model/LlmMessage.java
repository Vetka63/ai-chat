package com.example.aichat.common.llm.model;

/** Immutable message model used at the Llm boundary. */
public record LlmMessage(String role, String content) {
}
