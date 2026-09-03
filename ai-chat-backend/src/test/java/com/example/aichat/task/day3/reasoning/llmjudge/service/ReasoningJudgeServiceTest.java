package com.example.aichat.task.day3.reasoning.llmjudge.service;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.DeepSeekConfig;
import com.example.aichat.common.profile.model.InputPolicyConfig;
import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.task.day3.reasoning.config.ReasoningConfigProvider;
import com.example.aichat.task.day3.reasoning.config.ReasoningExperimentConfig;
import com.example.aichat.task.day3.reasoning.config.JudgeConfig;
import com.example.aichat.task.day3.reasoning.config.JudgeLlmConfig;
import com.example.aichat.task.day3.reasoning.llmjudge.model.ReasoningJudgeCandidate;
import com.example.aichat.task.day3.reasoning.llmjudge.model.ReasoningJudgeEvaluation;
import com.example.aichat.common.llm.LlmClient;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.llm.model.LlmResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class ReasoningJudgeServiceTest {

    @Test
    void anonymizesCandidatesAndMapsWinnerBackToStrategy() {
        var client = new RecordingLlmClient(List.of(validResponse()));
        var service = new ReasoningJudgeService(
                registry(),
                configProvider(),
                client,
                new ObjectMapper()
        );

        var result = service.judge(
                "day3-reasoning",
                "Исходная задача",
                candidates()
        );

        assertThat(result.winnerStrategy()).isEqualTo("step-by-step");
        assertThat(result.metrics().apiCalls()).isEqualTo(1);
        assertThat(result.winnerTitle()).isEqualTo("Пошаговое решение");
        assertThat(result.evaluations()).extracting(ReasoningJudgeEvaluation::strategy)
                .containsExactly("direct", "step-by-step");
        assertThat(result.evaluations().get(1).average()).isEqualTo(9.0);
        assertThat(client.calls.getFirst().model()).isEqualTo("deepseek-v4-pro");
        assertThat(client.calls.getFirst().thinking()).isEqualTo("disabled");
        var providerInput = client.calls.getFirst().messages().get(1).content();
        assertThat(providerInput)
                .contains("\"candidate\":\"A\"")
                .contains("\"candidate\":\"B\"")
                .doesNotContain("step-by-step")
                .doesNotContain("Прямой ответ");
    }

    @Test
    void retriesOnceWhenJudgeReturnsInvalidJson() {
        var client = new RecordingLlmClient(List.of(
                new LlmResult("not-json", "deepseek-chat", "stop"),
                validResponse()
        ));
        var service = new ReasoningJudgeService(
                registry(),
                configProvider(),
                client,
                new ObjectMapper()
        );

        var result = service.judge(
                "day3-reasoning",
                "Исходная задача",
                candidates()
        );

        assertThat(result.winnerStrategy()).isEqualTo("step-by-step");
        assertThat(result.metrics().apiCalls()).isEqualTo(2);
        assertThat(client.calls).extracting(Call::operation)
                .containsExactly("reasoning-judge", "reasoning-judge-retry");
        assertThat(client.calls).extracting(Call::model)
                .containsOnly("deepseek-v4-pro");
    }

    private static AgentRegistry registry() {
        var profile = mock(AgentProfile.class);
        var policy = mock(InputPolicyConfig.class);
        when(profile.id()).thenReturn("day3-reasoning");
        when(profile.experienceType()).thenReturn("reasoning-experiment");
        when(profile.systemPrompt()).thenReturn("Общий системный промпт");
        when(profile.inputPolicy()).thenReturn(policy);
        when(profile.deepseek()).thenReturn(new DeepSeekConfig(
                null,
                "disabled",
                null,
                0.2,
                null
        ));
        when(policy.maxMessageLength()).thenReturn(10_000);
        var registry = mock(AgentRegistry.class);
        when(registry.get("day3-reasoning")).thenReturn(profile);
        return registry;
    }

    private static ReasoningConfigProvider configProvider() {
        var provider = mock(ReasoningConfigProvider.class);
        when(provider.get(any(AgentProfile.class))).thenReturn(
                new ReasoningExperimentConfig(
                        null,
                        null,
                        null,
                        null,
                        new JudgeConfig(
                                new JudgeLlmConfig("deepseek-v4-pro", "disabled", null),
                                "Оцени обезличенные ответы",
                                1_800,
                                2,
                                4_000
                        )
                )
        );
        return provider;
    }

    private static List<ReasoningJudgeCandidate> candidates() {
        return List.of(
                new ReasoningJudgeCandidate("step-by-step", "Пошаговое решение задачи"),
                new ReasoningJudgeCandidate("direct", "Прямое решение задачи")
        );
    }

    private static LlmResult validResponse() {
        return new LlmResult(
                """
                {
                  "winner":"B",
                  "evaluations":[
                    {
                      "candidate":"A",
                      "scores":{"correctness":6,"clarity":7,"completeness":6,"efficiency":7,"edgeCases":4},
                      "strengths":"Краткий ответ",
                      "weaknesses":"Не проверены крайние случаи"
                    },
                    {
                      "candidate":"B",
                      "scores":{"correctness":9,"clarity":9,"completeness":9,"efficiency":9,"edgeCases":9},
                      "strengths":"Полное и проверенное решение",
                      "weaknesses":"Можно ответить короче"
                    }
                  ],
                  "explanation":"Кандидат B точнее и лучше проверен"
                }
                """,
                "deepseek-chat",
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
            calls.add(new Call(
                    request.operation(),
                    request.model(),
                    request.thinking(),
                    request.messages()
            ));
            return responses.removeFirst();
        }
    }

    private record Call(
            String operation,
            String model,
            String thinking,
            List<LlmMessage> messages
    ) {
    }
}
