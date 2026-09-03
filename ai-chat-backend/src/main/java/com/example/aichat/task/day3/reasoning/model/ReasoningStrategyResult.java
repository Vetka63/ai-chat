package com.example.aichat.task.day3.reasoning.model;

import com.example.aichat.task.day3.reasoning.enums.ReasoningStrategy;
import com.example.aichat.common.llm.model.LlmResult;

import java.util.List;

/** Immutable application result produced by the ReasoningStrategy workflow. */
public record ReasoningStrategyResult(
        String strategy,
        String title,
        String description,
        String status,
        String answer,
        String generatedPrompt,
        List<ExpertSolution> experts,
        String consensus,
        String comparison,
        String confidence,
        ExperimentMetrics metrics,
        String model,
        String finishReason,
        String error
) {
    public ReasoningStrategyResult {
        experts = experts == null ? List.of() : List.copyOf(experts);
        metrics = metrics == null ? ExperimentMetrics.empty() : metrics;
    }

    public static ReasoningStrategyResult answer(
            ReasoningStrategy strategy,
            LlmResult result
    ) {
        return success(
                strategy,
                result.content(),
                null,
                List.of(),
                null,
                null,
                null,
                result,
                List.of(result)
        );
    }

    public static ReasoningStrategyResult success(
            ReasoningStrategy strategy,
            String answer,
            String generatedPrompt,
            List<ExpertSolution> experts,
            String consensus,
            String comparison,
            String confidence,
            LlmResult result,
            List<LlmResult> calls
    ) {
        return new ReasoningStrategyResult(
                strategy.id(),
                strategy.title(),
                strategy.description(),
                "success",
                answer,
                generatedPrompt,
                experts,
                consensus,
                comparison,
                confidence,
                ExperimentMetrics.from(calls),
                result.model(),
                result.finishReason(),
                null
        );
    }

    public static ReasoningStrategyResult failure(
            ReasoningStrategy strategy,
            String error
    ) {
        return new ReasoningStrategyResult(
                strategy.id(),
                strategy.title(),
                strategy.description(),
                "error",
                null,
                null,
                List.of(),
                null,
                null,
                null,
                ExperimentMetrics.empty(),
                null,
                null,
                error
        );
    }

    public ReasoningStrategyResult withElapsedMs(long elapsedMs) {
        return new ReasoningStrategyResult(
                strategy,
                title,
                description,
                status,
                answer,
                generatedPrompt,
                experts,
                consensus,
                comparison,
                confidence,
                metrics.withElapsedMs(elapsedMs),
                model,
                finishReason,
                error
        );
    }
}
