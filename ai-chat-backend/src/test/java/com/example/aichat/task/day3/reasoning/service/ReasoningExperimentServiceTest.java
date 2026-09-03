package com.example.aichat.task.day3.reasoning.service;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.InputPolicyConfig;
import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.task.day3.reasoning.config.ReasoningConfigProvider;
import com.example.aichat.task.day3.reasoning.model.ReasoningExperimentContext;
import com.example.aichat.task.day3.reasoning.model.ReasoningStrategyResult;
import com.example.aichat.task.day3.reasoning.strategy.ReasoningStrategyExecutor;
import com.example.aichat.task.day3.reasoning.enums.ReasoningStrategy;
import com.example.aichat.common.llm.model.LlmResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class ReasoningExperimentServiceTest {

    @Test
    void runsAllStrategiesInStableOrder() {
        var registry = registry();
        var service = new ReasoningExperimentService(
                registry,
                configProvider(),
                Arrays.stream(ReasoningStrategy.values())
                        .<ReasoningStrategyExecutor>map(FakeExecutor::new)
                        .toList()
        );

        var experiment = service.run("day3-reasoning", "  Найди решение  ", List.of());

        assertThat(experiment.task()).isEqualTo("Найди решение");
        assertThat(experiment.results())
                .extracting(ReasoningStrategyResult::strategy)
                .containsExactly("direct", "step-by-step", "meta-prompt", "expert-panel");
        assertThat(experiment.results())
                .allMatch(result -> result.status().equals("success"));
        assertThat(experiment.metrics().apiCalls()).isEqualTo(4);
    }

    @Test
    void canRunOnlyOneStrategyForRetry() {
        var service = new ReasoningExperimentService(
                registry(),
                configProvider(),
                Arrays.stream(ReasoningStrategy.values())
                        .<ReasoningStrategyExecutor>map(FakeExecutor::new)
                        .toList()
        );

        var experiment = service.run(
                "day3-reasoning",
                "Задача",
                List.of("meta-prompt")
        );

        assertThat(experiment.results())
                .extracting(ReasoningStrategyResult::strategy)
                .containsExactly("meta-prompt");
        assertThat(experiment.metrics().apiCalls()).isEqualTo(1);
    }

    private static AgentRegistry registry() {
        var profile = mock(AgentProfile.class);
        var policy = mock(InputPolicyConfig.class);
        when(profile.id()).thenReturn("day3-reasoning");
        when(profile.experienceType()).thenReturn("reasoning-experiment");
        when(profile.inputPolicy()).thenReturn(policy);
        when(policy.maxMessageLength()).thenReturn(10_000);

        var registry = mock(AgentRegistry.class);
        when(registry.get("day3-reasoning")).thenReturn(profile);
        return registry;
    }

    private static ReasoningConfigProvider configProvider() {
        var provider = mock(ReasoningConfigProvider.class);
        when(provider.get(any(AgentProfile.class))).thenReturn(mock(
                com.example.aichat.task.day3.reasoning.config.ReasoningExperimentConfig.class
        ));
        return provider;
    }

    private record FakeExecutor(ReasoningStrategy strategy)
            implements ReasoningStrategyExecutor {

        @Override
        public ReasoningStrategyResult execute(ReasoningExperimentContext context) {
            return ReasoningStrategyResult.answer(
                    strategy,
                    new LlmResult("Ответ " + strategy.id(), "deepseek-chat", "stop")
            );
        }
    }
}
