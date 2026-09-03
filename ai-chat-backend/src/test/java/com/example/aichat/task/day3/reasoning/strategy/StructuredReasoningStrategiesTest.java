package com.example.aichat.task.day3.reasoning.strategy;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.DeepSeekConfig;
import com.example.aichat.task.day3.reasoning.config.ExpertPanelConfig;
import com.example.aichat.task.day3.reasoning.config.ExpertRoleConfig;
import com.example.aichat.task.day3.reasoning.config.JudgeConfig;
import com.example.aichat.task.day3.reasoning.config.MetaPromptConfig;
import com.example.aichat.task.day3.reasoning.config.ReasoningExperimentConfig;
import com.example.aichat.task.day3.reasoning.config.ReasoningStrategyConfig;
import com.example.aichat.task.day3.reasoning.model.ReasoningExperimentContext;
import com.example.aichat.task.day3.reasoning.model.ExpertSolution;
import com.example.aichat.common.llm.LlmClient;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.llm.model.LlmResult;
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
        return new ReasoningExperimentContext(
                profile(),
                reasoningConfig(expertPanel()),
                "Исходная задача"
        );
    }

    private static ReasoningExperimentContext expertContext() {
        return new ReasoningExperimentContext(
                profile(),
                reasoningConfig(expertPanel()),
                "Исходная задача"
        );
    }

    private static AgentProfile profile() {
        var profile = mock(AgentProfile.class);
        when(profile.id()).thenReturn("day3-reasoning");
        when(profile.systemPrompt()).thenReturn("Системный промпт");
        when(profile.deepseek()).thenReturn(new DeepSeekConfig(
                null,
                "disabled",
                null,
                0.2,
                null
        ));
        return profile;
    }

    private static ExpertPanelConfig expertPanel() {
        var roles = List.of(
                new ExpertRoleConfig(
                        "analyst",
                        "Аналитик",
                        "Формализуй задачу"
                ),
                new ExpertRoleConfig(
                        "engineer",
                        "Инженер",
                        "Построй алгоритм"
                ),
                new ExpertRoleConfig(
                        "critic",
                        "Критик",
                        "Проверь крайние случаи"
                )
        );
        return new ExpertPanelConfig(
                "Работай независимо",
                roles,
                "Сравни решения и верни JSON",
                1_600,
                1_400
        );
    }

    private static ReasoningExperimentConfig reasoningConfig(ExpertPanelConfig panel) {
        return new ReasoningExperimentConfig(
                new ReasoningStrategyConfig(null, 1_800),
                new ReasoningStrategyConfig("Решай пошагово", 1_800),
                new MetaPromptConfig(
                        "Составь промпт и верни JSON",
                        "Следуй промпту",
                        600,
                        1_800,
                        6_000
                ),
                panel,
                new JudgeConfig("Оцени решения", 1_800, 2, 4_000)
        );
    }

    private static final class RecordingLlmClient implements LlmClient {
        private final List<LlmResult> responses;
        private final List<List<LlmMessage>> calls = new ArrayList<>();
        private int responseIndex;

        private RecordingLlmClient(List<LlmResult> responses) {
            this.responses = responses;
        }

        @Override
        public LlmResult complete(LlmCompletionRequest request) {
            calls.add(request.messages());
            return responses.get(responseIndex++);
        }
    }

    private static final class RoutingLlmClient implements LlmClient {
        private final Map<String, List<LlmMessage>> calls = new ConcurrentHashMap<>();

        @Override
        public LlmResult complete(LlmCompletionRequest request) {
            calls.put(request.operation(), request.messages());
            var content = switch (request.operation()) {
                case "expert-analyst" -> "Решение А";
                case "expert-engineer" -> "Решение Б";
                case "expert-critic" -> "Проверка В";
                case "expert-synthesis" -> """
                        {"consensus":"Общий ответ","comparison":"Сравнение решений","confidence":"high"}
                        """;
                default -> throw new IllegalArgumentException(
                        "Unexpected operation: " + request.operation()
                );
            };
            return new LlmResult(content, "deepseek-chat", "stop");
        }
    }
}
