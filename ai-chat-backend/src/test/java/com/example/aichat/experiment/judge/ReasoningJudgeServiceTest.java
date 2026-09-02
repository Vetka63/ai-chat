package com.example.aichat.experiment.judge;

import com.example.aichat.agent.AgentProfile;
import com.example.aichat.agent.AgentProfile.ResponseModeConfig;
import com.example.aichat.agent.AgentRegistry;
import com.example.aichat.llm.LlmClient;
import com.example.aichat.llm.LlmMessage;
import com.example.aichat.llm.LlmResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReasoningJudgeServiceTest {

    @Test
    void anonymizesCandidatesAndMapsWinnerBackToStrategy() {
        var client = new RecordingLlmClient(List.of(validResponse()));
        var service = new ReasoningJudgeService(registry(), client, new ObjectMapper());

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
        var service = new ReasoningJudgeService(registry(), client, new ObjectMapper());

        var result = service.judge(
                "day3-reasoning",
                "Исходная задача",
                candidates()
        );

        assertThat(result.winnerStrategy()).isEqualTo("step-by-step");
        assertThat(result.metrics().apiCalls()).isEqualTo(2);
        assertThat(client.calls).extracting(Call::operation)
                .containsExactly("reasoning-judge", "reasoning-judge-retry");
    }

    private static AgentRegistry registry() {
        var profile = mock(AgentProfile.class);
        var policy = mock(AgentProfile.InputPolicyConfig.class);
        when(profile.id()).thenReturn("day3-reasoning");
        when(profile.experienceType()).thenReturn("reasoning-experiment");
        when(profile.systemPrompt()).thenReturn("Общий системный промпт");
        when(profile.inputPolicy()).thenReturn(policy);
        when(policy.maxMessageLength()).thenReturn(10_000);
        when(profile.reasoningExperiment()).thenReturn(
                new AgentProfile.ReasoningExperimentConfig(
                        null,
                        new AgentProfile.JudgeConfig("Оцени обезличенные ответы", 1_800)
                )
        );

        var registry = mock(AgentRegistry.class);
        when(registry.get("day3-reasoning")).thenReturn(profile);
        return registry;
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
        public LlmResult complete(
                AgentProfile profile,
                ResponseModeConfig responseMode,
                List<LlmMessage> messages
        ) {
            calls.add(new Call(responseMode.id(), messages));
            return responses.removeFirst();
        }
    }

    private record Call(
            String operation,
            List<LlmMessage> messages
    ) {
    }
}
