package com.example.aichat.task.day4.temperature.service;

import com.example.aichat.common.llm.LlmClient;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.DeepSeekConfig;
import com.example.aichat.common.profile.model.InputPolicyConfig;
import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.task.day4.temperature.config.TemperatureConfigProvider;
import com.example.aichat.task.day4.temperature.config.TemperatureExperimentConfig;
import com.example.aichat.task.day4.temperature.config.TemperatureJudgeConfig;
import com.example.aichat.task.day4.temperature.config.TemperatureJudgeLlmConfig;
import com.example.aichat.task.day4.temperature.config.TemperatureVariantConfig;
import com.example.aichat.task.day4.temperature.enums.TemperatureResultStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemperatureExperimentServiceTest {

    @Test
    void changesOnlyTemperatureAndKeepsStableResultOrder() {
        var fixture = fixture();
        when(fixture.llmClient.complete(any())).thenAnswer(invocation -> {
            LlmCompletionRequest request = invocation.getArgument(0);
            return new LlmResult(
                    "Ответ для " + request.temperature(),
                    request.model(),
                    "stop"
            );
        });

        var experiment = fixture.service.run("day4-temperature", "  Придумай идею  ");

        assertThat(experiment.task()).isEqualTo("Придумай идею");
        assertThat(experiment.results())
                .extracting(result -> result.temperature())
                .containsExactly(0.0, 0.7, 1.2, 2.0);
        assertThat(experiment.results())
                .allMatch(result -> result.status() == TemperatureResultStatus.SUCCESS);
        assertThat(experiment.metrics().apiCalls()).isEqualTo(4);

        var requests = ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(fixture.llmClient, times(4)).complete(requests.capture());
        assertThat(requests.getAllValues())
                .extracting(LlmCompletionRequest::temperature)
                .containsExactly(0.0, 0.7, 1.2, 2.0);
        assertThat(requests.getAllValues())
                .extracting(LlmCompletionRequest::model)
                .containsOnly("deepseek-v4-flash");
        assertThat(requests.getAllValues())
                .extracting(LlmCompletionRequest::thinking)
                .containsOnly("disabled");
        assertThat(requests.getAllValues())
                .extracting(LlmCompletionRequest::topP)
                .containsOnly(1.0);
        assertThat(requests.getAllValues())
                .extracting(LlmCompletionRequest::messages)
                .containsOnly(requests.getAllValues().getFirst().messages());
        assertThat(requests.getAllValues().getFirst().messages())
                .extracting(message -> message.role())
                .containsExactly("system", "user");
    }

    @Test
    void preservesSuccessfulAnswersWhenOneTemperatureFails() {
        var fixture = fixture();
        when(fixture.llmClient.complete(any())).thenAnswer(invocation -> {
            LlmCompletionRequest request = invocation.getArgument(0);
            if (Double.compare(request.temperature(), 0.7) == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "provider unavailable");
            }
            return new LlmResult("Ответ", request.model(), "stop");
        });

        var experiment = fixture.service.run("day4-temperature", "Задача");

        assertThat(experiment.results())
                .extracting(result -> result.status())
                .containsExactly(
                        TemperatureResultStatus.SUCCESS,
                        TemperatureResultStatus.ERROR,
                        TemperatureResultStatus.SUCCESS,
                        TemperatureResultStatus.SUCCESS
                );
        assertThat(experiment.results().get(1).error())
                .isEqualTo("Не удалось получить ответ DeepSeek");
        assertThat(experiment.metrics().apiCalls()).isEqualTo(4);
    }

    private static Fixture fixture() {
        var profile = mock(AgentProfile.class);
        var inputPolicy = mock(InputPolicyConfig.class);
        when(profile.id()).thenReturn("day4-temperature");
        when(profile.experienceType()).thenReturn(TemperatureConfigProvider.EXPERIENCE_TYPE);
        when(profile.systemPrompt()).thenReturn("Системная инструкция");
        when(profile.inputPolicy()).thenReturn(inputPolicy);
        when(inputPolicy.maxMessageLength()).thenReturn(10_000);
        when(profile.deepseek()).thenReturn(new DeepSeekConfig(
                "deepseek-v4-flash",
                "disabled",
                null,
                0.2,
                1.0
        ));

        var registry = mock(AgentRegistry.class);
        when(registry.get("day4-temperature")).thenReturn(profile);
        var configProvider = mock(TemperatureConfigProvider.class);
        when(configProvider.get(profile)).thenReturn(new TemperatureExperimentConfig(
                List.of(
                        new TemperatureVariantConfig("precise", "Точный", "Описание", 0.0),
                        new TemperatureVariantConfig("balanced", "Баланс", "Описание", 0.7),
                        new TemperatureVariantConfig("creative", "Творческий", "Описание", 1.2),
                        new TemperatureVariantConfig("experimental", "Экспериментальный", "Описание", 2.0)
                ),
                1200,
                new TemperatureJudgeConfig(
                        new TemperatureJudgeLlmConfig(
                                "deepseek-v4-pro", "disabled", null, 0.0, 1.0
                        ),
                        "Инструкция судьи",
                        1800,
                        2,
                        3600
                )
        ));
        var llmClient = mock(LlmClient.class);
        return new Fixture(
                new TemperatureExperimentService(registry, configProvider, llmClient),
                llmClient
        );
    }

    private record Fixture(
            TemperatureExperimentService service,
            LlmClient llmClient
    ) {
    }
}
