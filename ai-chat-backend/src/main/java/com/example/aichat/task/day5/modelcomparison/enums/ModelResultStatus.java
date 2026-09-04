package com.example.aichat.task.day5.modelcomparison.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** Перечисляет публичные состояния вызова одной модели в эксперименте Дня 5. */
public enum ModelResultStatus {
    SUCCESS("success"),
    ERROR("error");

    private final String value;

    ModelResultStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
