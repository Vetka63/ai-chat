package com.example.aichat.common.llmjudge;

/**
 * Generic extension point for task-specific LLM judges.
 *
 * @param <I> judge command type
 * @param <O> validated judge result type
 */
public interface LlmJudge<I, O> {

    /** Returns the stable registry identifier. */
    String type();

    /** Declares the supported command type for safe registry lookup. */
    Class<I> inputType();

    /** Declares the produced result type for safe registry lookup. */
    Class<O> resultType();

    /** Evaluates a task-specific command and returns a validated result. */
    O judge(I input);
}
