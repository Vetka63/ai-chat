package com.example.aichat.common.llm.model;

import java.math.BigDecimal;
import java.util.Collection;

/** Агрегирует метрики нескольких независимых LLM-вызовов без привязки к задаче. */
public record AggregateLlmMetrics(
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
    public static AggregateLlmMetrics empty() {
        return new AggregateLlmMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, null);
    }

    public static AggregateLlmMetrics from(Collection<LlmResult> results, long elapsedMs) {
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

        return new AggregateLlmMetrics(
                apiCalls,
                Math.max(0, elapsedMs),
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

    /** Возвращает те же метрики с фактическим числом предпринятых API-вызовов. */
    public AggregateLlmMetrics withApiCalls(int value) {
        return new AggregateLlmMetrics(
                Math.max(0, value),
                elapsedMs,
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
