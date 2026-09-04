package com.example.aichat.common.llm.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** Перечисляет LLM-провайдеров, доступных через общий серверный контракт. */
public enum LlmProvider {
    DEEPSEEK("deepseek"),
    MISTRAL("mistral");

    private final String id;

    LlmProvider(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    @JsonCreator
    public static LlmProvider from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("LLM provider must not be null");
        }
        return Arrays.stream(values())
                .filter(provider -> provider.id.equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported LLM provider: " + value
                ));
    }
}
