package com.example.aichat.task.day3.reasoning.model;

/** Представляет решение, сформированное одной ролью экспертной группы Дня 3. */
public record ExpertSolution(
        String role,
        String solution
) {
}
