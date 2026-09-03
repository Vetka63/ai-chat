package com.example.aichat.task.day3.reasoning.enums;

import java.util.Arrays;

/** Closed set of supported values represented by ReasoningStrategy. */

public enum ReasoningStrategy {
    DIRECT(
            "direct",
            "Прямой ответ",
            "Модель получает задачу без дополнительных инструкций"
    ),
    STEP_BY_STEP(
            "step-by-step",
            "Пошаговое решение",
            "К задаче добавляется инструкция «решай пошагово»"
    ),
    META_PROMPT(
            "meta-prompt",
            "Промпт для решения",
            "Модель сначала составляет промпт, а затем решает задачу по нему"
    ),
    EXPERT_PANEL(
            "expert-panel",
            "Группа экспертов",
            "Аналитик, инженер и критик рассматривают задачу с разных сторон"
    );

    private final String id;
    private final String title;
    private final String description;

    ReasoningStrategy(String id, String title, String description) {
        this.id = id;
        this.title = title;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public static ReasoningStrategy fromId(String id) {
        return Arrays.stream(values())
                .filter(strategy -> strategy.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown reasoning strategy: " + id));
    }
}
