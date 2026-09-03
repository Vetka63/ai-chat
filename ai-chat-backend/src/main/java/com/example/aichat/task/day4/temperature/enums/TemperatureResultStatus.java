package com.example.aichat.task.day4.temperature.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/** Перечисляет публичные состояния одного температурного запуска. */
public enum TemperatureResultStatus {
    SUCCESS("success"),
    ERROR("error");

    private final String value;

    TemperatureResultStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
