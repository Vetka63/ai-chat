package com.example.aichat.task.day3.reasoning.model;

import com.example.aichat.common.llm.model.LlmMetrics;
import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.llm.model.LlmUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentMetricsTest {

    @Test
    void aggregatesAllProviderCalls() {
        var first = new LlmResult(
                "Первый ответ",
                "deepseek-chat",
                "stop",
                new LlmMetrics(
                        100,
                        new LlmUsage(20, 10, 30, 5, 15, 2),
                        new BigDecimal("0.00001000")
                )
        );
        var second = new LlmResult(
                "Второй ответ",
                "deepseek-chat",
                "stop",
                new LlmMetrics(
                        250,
                        new LlmUsage(40, 20, 60, 10, 30, 5),
                        new BigDecimal("0.00002000")
                )
        );

        var metrics = ExperimentMetrics.from(List.of(first, second)).withElapsedMs(275);

        assertThat(metrics.apiCalls()).isEqualTo(2);
        assertThat(metrics.elapsedMs()).isEqualTo(275);
        assertThat(metrics.apiDurationMs()).isEqualTo(350);
        assertThat(metrics.promptTokens()).isEqualTo(60);
        assertThat(metrics.completionTokens()).isEqualTo(30);
        assertThat(metrics.totalTokens()).isEqualTo(90);
        assertThat(metrics.reasoningTokens()).isEqualTo(7);
        assertThat(metrics.estimatedCostUsd()).isEqualByComparingTo("0.00003000");
    }
}
