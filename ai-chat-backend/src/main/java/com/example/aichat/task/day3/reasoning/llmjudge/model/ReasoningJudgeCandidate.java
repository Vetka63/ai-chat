package com.example.aichat.task.day3.reasoning.llmjudge.model;

/** Представляет кандидатский ответ для LLM-судьи Дня 3. */
public record ReasoningJudgeCandidate(
        String strategy,
        String answer
) {
}
