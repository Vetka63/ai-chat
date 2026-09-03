package com.example.aichat.task.day3.reasoning.model;

import com.example.aichat.common.llm.model.LlmResult;

import java.math.BigDecimal;
import java.util.Collection;

/** Aggregated execution metrics for Experiment calls. */
public record ExperimentMetrics(
        int apiCalls,
        long elapsedMs,
        long apiDurationMs,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long promptCacheHitTokens,
        long promptCacheMissTokens,
        long reasoningTokens,
        BigDecimal estimatedCostUsd
) {
    public static ExperimentMetrics empty() {
        return new ExperimentMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, null);
    }

    public static ExperimentMetrics from(Collection<LlmResult> results) {
        var apiCalls = 0;
        var apiDurationMs = 0L;
        var promptTokens = 0L;
        var completionTokens = 0L;
        var totalTokens = 0L;
        var promptCacheHitTokens = 0L;
        var promptCacheMissTokens = 0L;
        var reasoningTokens = 0L;
        BigDecimal cost = null;

        for (var result : results) {
            if (result == null) {
                continue;
            }
            apiCalls++;
            var metrics = result.metrics();
            apiDurationMs += metrics.durationMs();
            var usage = metrics.usage();
            promptTokens += usage.promptTokens();
            completionTokens += usage.completionTokens();
            totalTokens += usage.totalTokens();
            promptCacheHitTokens += usage.promptCacheHitTokens();
            promptCacheMissTokens += usage.promptCacheMissTokens();
            reasoningTokens += usage.reasoningTokens();
            if (metrics.estimatedCostUsd() != null) {
                cost = cost == null
                        ? metrics.estimatedCostUsd()
                        : cost.add(metrics.estimatedCostUsd());
            }
        }

        return new ExperimentMetrics(
                apiCalls,
                apiDurationMs,
                apiDurationMs,
                promptTokens,
                completionTokens,
                totalTokens,
                promptCacheHitTokens,
                promptCacheMissTokens,
                reasoningTokens,
                cost
        );
    }

    public static ExperimentMetrics combine(Collection<ExperimentMetrics> metricsCollection) {
        var apiCalls = 0;
        var elapsedMs = 0L;
        var apiDurationMs = 0L;
        var promptTokens = 0L;
        var completionTokens = 0L;
        var totalTokens = 0L;
        var promptCacheHitTokens = 0L;
        var promptCacheMissTokens = 0L;
        var reasoningTokens = 0L;
        BigDecimal cost = null;

        for (var metrics : metricsCollection) {
            if (metrics == null) {
                continue;
            }
            apiCalls += metrics.apiCalls();
            elapsedMs += metrics.elapsedMs();
            apiDurationMs += metrics.apiDurationMs();
            promptTokens += metrics.promptTokens();
            completionTokens += metrics.completionTokens();
            totalTokens += metrics.totalTokens();
            promptCacheHitTokens += metrics.promptCacheHitTokens();
            promptCacheMissTokens += metrics.promptCacheMissTokens();
            reasoningTokens += metrics.reasoningTokens();
            if (metrics.estimatedCostUsd() != null) {
                cost = cost == null
                        ? metrics.estimatedCostUsd()
                        : cost.add(metrics.estimatedCostUsd());
            }
        }

        return new ExperimentMetrics(
                apiCalls,
                elapsedMs,
                apiDurationMs,
                promptTokens,
                completionTokens,
                totalTokens,
                promptCacheHitTokens,
                promptCacheMissTokens,
                reasoningTokens,
                cost
        );
    }

    public ExperimentMetrics withElapsedMs(long value) {
        return new ExperimentMetrics(
                apiCalls,
                Math.max(0, value),
                apiDurationMs,
                promptTokens,
                completionTokens,
                totalTokens,
                promptCacheHitTokens,
                promptCacheMissTokens,
                reasoningTokens,
                estimatedCostUsd
        );
    }
}
