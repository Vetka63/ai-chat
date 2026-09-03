package com.example.aichat.task.day4.temperature.llmjudge.service;

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
import com.example.aichat.task.day4.temperature.llmjudge.model.TemperatureJudgeCandidate;
import com.example.aichat.task.day4.temperature.llmjudge.model.TemperatureJudgeCommand;
import com.example.aichat.task.day4.temperature.llmjudge.model.TemperatureJudgeEvaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemperatureJudgeServiceTest {

    @Test
    void anonymizesTemperaturesAndMapsWinnerBackToVariant() {
        var client = new RecordingLlmClient(List.of(validResponse()));
        var service = new TemperatureJudgeService(registry(), configProvider(), client, new ObjectMapper());

        var result = service.judge(command());

        assertThat(result.winnerVariantId()).isEqualTo("experimental");
        assertThat(result.winnerTemperature()).isEqualTo(2.0);
        assertThat(result.diversityScore()).isEqualTo(8);
        assertThat(result.metrics().apiCalls()).isEqualTo(1);
        assertThat(result.evaluations()).extracting(TemperatureJudgeEvaluation::variantId)
                .containsExactly("precise", "experimental");
        assertThat(client.calls.getFirst().model()).isEqualTo("deepseek-v4-pro");
        assertThat(client.calls.getFirst().thinking()).isEqualTo("disabled");
        assertThat(client.calls.getFirst().temperature()).isEqualTo(0.0);
        assertThat(client.calls.getFirst().topP()).isEqualTo(1.0);
        var providerInput = client.calls.getFirst().request().messages().get(1).content();
        assertThat(providerInput)
                .contains("\"candidate\":\"A\"")
                .contains("\"candidate\":\"B\"")
                .doesNotContain("experimental")
                .doesNotContain("Экспериментальный")
                .doesNotContain("2.0");
    }

    @Test
    void retriesOnceWhenJudgeReturnsInvalidJson() {
        var client = new RecordingLlmClient(List.of(
                new LlmResult("not-json", "deepseek-v4-pro", "stop"),
                validResponse()
        ));
        var service = new TemperatureJudgeService(registry(), configProvider(), client, new ObjectMapper());

        var result = service.judge(command());

        assertThat(result.winnerVariantId()).isEqualTo("experimental");
        assertThat(result.metrics().apiCalls()).isEqualTo(2);
        assertThat(client.calls).extracting(call -> call.request().operation())
                .containsExactly("temperature-judge", "temperature-judge-retry");
        assertThat(client.calls).extracting(Call::model).containsOnly("deepseek-v4-pro");
    }

    private static TemperatureJudgeCommand command() {
        return new TemperatureJudgeCommand(
                "day4-temperature",
                "Придумай название",
                List.of(
                        new TemperatureJudgeCandidate("experimental", "Необычный ответ"),
                        new TemperatureJudgeCandidate("precise", "Точный ответ")
                )
        );
    }

    private static AgentRegistry registry() {
        var profile = mock(AgentProfile.class);
        var policy = mock(InputPolicyConfig.class);
        when(profile.id()).thenReturn("day4-temperature");
        when(profile.experienceType()).thenReturn(TemperatureConfigProvider.EXPERIENCE_TYPE);
        when(profile.systemPrompt()).thenReturn("Общий системный промпт");
        when(profile.inputPolicy()).thenReturn(policy);
        when(profile.deepseek()).thenReturn(new DeepSeekConfig(
                "deepseek-v4-flash", "disabled", null, null, 1.0
        ));
        when(policy.maxMessageLength()).thenReturn(10_000);
        var registry = mock(AgentRegistry.class);
        when(registry.get("day4-temperature")).thenReturn(profile);
        return registry;
    }

    private static TemperatureConfigProvider configProvider() {
        var provider = mock(TemperatureConfigProvider.class);
        when(provider.get(any(AgentProfile.class))).thenReturn(new TemperatureExperimentConfig(
                List.of(
                        new TemperatureVariantConfig("precise", "Точный", "Описание", 0.0),
                        new TemperatureVariantConfig("experimental", "Экспериментальный", "Описание", 2.0)
                ),
                1200,
                new TemperatureJudgeConfig(
                        new TemperatureJudgeLlmConfig(
                                "deepseek-v4-pro", "disabled", null, 0.0, 1.0
                        ),
                        "Оцени обезличенные ответы",
                        1800,
                        2,
                        3600
                )
        ));
        return provider;
    }

    private static LlmResult validResponse() {
        return new LlmResult(
                """
                {
                  "winner":"B",
                  "evaluations":[
                    {
                      "candidate":"A",
                      "scores":{"accuracy":9,"creativity":4,"instructionFollowing":10},
                      "strengths":"Точный и полный ответ",
                      "weaknesses":"Мало оригинальности"
                    },
                    {
                      "candidate":"B",
                      "scores":{"accuracy":8,"creativity":10,"instructionFollowing":9},
                      "strengths":"Оригинальная и полезная идея",
                      "weaknesses":"Есть небольшой риск неточности"
                    }
                  ],
                  "diversity":{"score":8,"explanation":"Подходы заметно различаются"},
                  "explanation":"Кандидат B лучше сочетает качество и оригинальность"
                }
                """,
                "deepseek-v4-pro",
                "stop"
        );
    }

    private static final class RecordingLlmClient implements LlmClient {
        private final ArrayDeque<LlmResult> responses;
        private final List<Call> calls = new ArrayList<>();

        private RecordingLlmClient(List<LlmResult> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public LlmResult complete(LlmCompletionRequest request) {
            calls.add(new Call(request));
            return responses.removeFirst();
        }
    }

    private record Call(LlmCompletionRequest request) {
        private String model() {
            return request.model();
        }

        private String thinking() {
            return request.thinking();
        }

        private Double temperature() {
            return request.temperature();
        }

        private Double topP() {
            return request.topP();
        }
    }
}
