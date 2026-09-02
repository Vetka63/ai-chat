package com.example.aichat.experiment;

import com.example.aichat.agent.AgentProfile;
import com.example.aichat.agent.AgentProfile.ResponseModeConfig;
import com.example.aichat.experiment.strategy.ExpertPanelStrategyExecutor;
import com.example.aichat.experiment.strategy.MetaPromptStrategyExecutor;
import com.example.aichat.llm.LlmClient;
import com.example.aichat.llm.LlmMessage;
import com.example.aichat.llm.LlmResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredReasoningStrategiesTest {

    @Test
    void metaPromptUsesGeneratedPromptInASecondCall() {
        var client = new RecordingLlmClient(List.of(
                new LlmResult("{\"prompt\":\"Проверь варианты и вычисли ответ\"}", "deepseek-chat", "stop"),
                new LlmResult("Итоговое решение", "deepseek-chat", "stop")
        ));
        var executor = new MetaPromptStrategyExecutor(client, new ObjectMapper());

        var result = executor.execute(context());

        assertThat(result.generatedPrompt()).isEqualTo("Проверь варианты и вычисли ответ");
        assertThat(result.answer()).isEqualTo("Итоговое решение");
        assertThat(client.calls).hasSize(2);
        assertThat(client.calls.get(1).get(1).content())
                .contains("Проверь варианты и вычисли ответ")
                .contains("Исходная задача");
    }

    @Test
    void expertPanelRunsIndependentExpertsAndThenSynthesizesTheirAnswers() {
        var client = new RoutingLlmClient();
        var executor = new ExpertPanelStrategyExecutor(client, new ObjectMapper());

        var result = executor.execute(expertContext());

        assertThat(result.experts()).extracting(ExpertSolution::role)
                .containsExactly("Аналитик", "Инженер", "Критик");
        assertThat(result.consensus()).isEqualTo("Общий ответ");
        assertThat(result.comparison()).isEqualTo("Сравнение решений");
        assertThat(result.confidence()).isEqualTo("high");
        assertThat(client.calls).containsOnlyKeys(
                "expert-analyst",
                "expert-engineer",
                "expert-critic",
                "expert-synthesis"
        );
        assertThat(client.calls.get("expert-analyst").getFirst().content())
                .contains("Формализуй задачу");
        assertThat(client.calls.get("expert-synthesis").get(1).content())
                .contains("Решение А")
                .contains("Решение Б")
                .contains("Проверка В");
    }

    private static ReasoningExperimentContext context() {
        var profile = mock(AgentProfile.class);
        when(profile.systemPrompt()).thenReturn("Системный промпт");
        return new ReasoningExperimentContext(profile, "Исходная задача");
    }

    private static ReasoningExperimentContext expertContext() {
        var profile = mock(AgentProfile.class);
        when(profile.systemPrompt()).thenReturn("Системный промпт");
        var roles = List.of(
                new AgentProfile.ExpertRoleConfig(
                        "analyst",
                        "Аналитик",
                        "Формализуй задачу"
                ),
                new AgentProfile.ExpertRoleConfig(
                        "engineer",
                        "Инженер",
                        "Построй алгоритм"
                ),
                new AgentProfile.ExpertRoleConfig(
                        "critic",
                        "Критик",
                        "Проверь крайние случаи"
                )
        );
        var panel = new AgentProfile.ExpertPanelConfig(
                "Работай независимо",
                roles,
                "Сравни решения и верни JSON",
                1_600,
                1_400
        );
        when(profile.reasoningExperiment()).thenReturn(
                new AgentProfile.ReasoningExperimentConfig(
                        panel,
                        new AgentProfile.JudgeConfig("Оцени решения", 1_800)
                )
        );
        return new ReasoningExperimentContext(profile, "Исходная задача");
    }

    private static final class RecordingLlmClient implements LlmClient {
        private final List<LlmResult> responses;
        private final List<List<LlmMessage>> calls = new ArrayList<>();
        private int responseIndex;

        private RecordingLlmClient(List<LlmResult> responses) {
            this.responses = responses;
        }

        @Override
        public LlmResult complete(
                AgentProfile profile,
                ResponseModeConfig responseMode,
                List<LlmMessage> messages
        ) {
            calls.add(messages);
            return responses.get(responseIndex++);
        }
    }

    private static final class RoutingLlmClient implements LlmClient {
        private final Map<String, List<LlmMessage>> calls = new ConcurrentHashMap<>();

        @Override
        public LlmResult complete(
                AgentProfile profile,
                ResponseModeConfig responseMode,
                List<LlmMessage> messages
        ) {
            calls.put(responseMode.id(), messages);
            var content = switch (responseMode.id()) {
                case "expert-analyst" -> "Решение А";
                case "expert-engineer" -> "Решение Б";
                case "expert-critic" -> "Проверка В";
                case "expert-synthesis" -> """
                        {"consensus":"Общий ответ","comparison":"Сравнение решений","confidence":"high"}
                        """;
                default -> throw new IllegalArgumentException(
                        "Unexpected operation: " + responseMode.id()
                );
            };
            return new LlmResult(content, "deepseek-chat", "stop");
        }
    }
}
