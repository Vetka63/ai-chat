package com.example.aichat.task.day5.modelcomparison.llmjudge.service;

import com.example.aichat.common.llm.LlmProviderClient;
import com.example.aichat.common.llm.LlmProviderRegistry;
import com.example.aichat.common.llm.enums.LlmProvider;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmMetrics;
import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.llm.model.LlmUsage;
import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.config.ChatProperties;
import com.example.aichat.config.enums.Mode;
import com.example.aichat.task.day5.modelcomparison.config.ModelComparisonConfigProvider;
import com.example.aichat.task.day5.modelcomparison.llmjudge.model.ModelComparisonJudgeCandidate;
import com.example.aichat.task.day5.modelcomparison.llmjudge.model.ModelComparisonJudgeCommand;
import com.example.aichat.task.day5.modelcomparison.llmjudge.model.ModelComparisonJudgeEvaluation;
import com.example.aichat.task.day5.modelcomparison.service.ModelCostCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelComparisonJudgeServiceTest {

    @Test
    void anonymizesModelsMapsWinnerAndCalculatesJudgeCost() {
        var client = new RecordingDeepSeekClient(List.of(validResponse()));
        var service = service(client);

        var result = service.judge(command());

        assertThat(result.winnerModelId()).isEqualTo("strong");
        assertThat(result.winnerTitle()).isEqualTo("Сильная модель");
        assertThat(result.summary()).contains("Кандидат C");
        assertThat(result.evaluations()).extracting(ModelComparisonJudgeEvaluation::modelId)
                .containsExactly("weak", "medium", "strong");
        assertThat(result.metrics().apiCalls()).isEqualTo(1);
        assertThat(result.metrics().estimatedCostUsd()).isEqualByComparingTo("0.00052800");

        var request = client.requests.getFirst();
        assertThat(request.model()).isEqualTo("deepseek-v4-pro");
        assertThat(request.temperature()).isZero();
        assertThat(request.responseFormat()).isEqualTo("json_object");
        var providerInput = request.messages().get(1).content();
        assertThat(providerInput)
                .contains("\"candidate\":\"A\"")
                .contains("\"candidate\":\"B\"")
                .contains("\"candidate\":\"C\"")
                .doesNotContain("ministral-3b-2512")
                .doesNotContain("deepseek-v4-flash")
                .doesNotContain("deepseek-v4-pro")
                .doesNotContain("Слабая модель")
                .doesNotContain("Сильная модель");
    }

    @Test
    void retriesAnInvalidJudgeResponse() {
        var client = new RecordingDeepSeekClient(List.of(
                new LlmResult("not-json", "deepseek-v4-pro", "stop"),
                validResponse()
        ));

        var result = service(client).judge(command());

        assertThat(result.winnerModelId()).isEqualTo("strong");
        assertThat(result.metrics().apiCalls()).isEqualTo(2);
        assertThat(client.requests).extracting(LlmCompletionRequest::operation)
                .containsExactly("model-comparison-judge", "model-comparison-judge-retry");
    }

    private static ModelComparisonJudgeService service(LlmProviderClient client) {
        var properties = new ChatProperties(
                Mode.LLM,
                "https://api.deepseek.com",
                "key",
                "deepseek-v4-flash",
                "classpath*:agents/*.yml",
                "general",
                false,
                ChatProperties.Pricing.free(),
                List.of("http://localhost:5173")
        );
        var registry = new AgentRegistry(new PathMatchingResourcePatternResolver(), properties);
        var mapper = new ObjectMapper().findAndRegisterModules();
        return new ModelComparisonJudgeService(
                registry,
                new ModelComparisonConfigProvider(mapper),
                new LlmProviderRegistry(List.of(client)),
                new ModelCostCalculator(),
                mapper
        );
    }

    private static ModelComparisonJudgeCommand command() {
        return new ModelComparisonJudgeCommand(
                "day5-model-comparison",
                "Реши алгоритмическую задачу",
                List.of(
                        new ModelComparisonJudgeCandidate("strong", "Ответ сильной модели"),
                        new ModelComparisonJudgeCandidate("weak", "Ответ слабой модели"),
                        new ModelComparisonJudgeCandidate("medium", "Ответ средней модели")
                )
        );
    }

    private static LlmResult validResponse() {
        return new LlmResult(
                """
                {
                  "winner":"C",
                  "evaluations":[
                    {"candidate":"A","scores":{"accuracy":6,"instructionFollowing":7,"completeness":6,"clarity":8},"strengths":"Понятно","weaknesses":"Есть пропуски"},
                    {"candidate":"B","scores":{"accuracy":8,"instructionFollowing":8,"completeness":8,"clarity":8},"strengths":"Корректно","weaknesses":"Мало деталей"},
                    {"candidate":"C","scores":{"accuracy":10,"instructionFollowing":10,"completeness":9,"clarity":9},"strengths":"Точно и полно","weaknesses":"Можно короче"}
                  ],
                  "summary":"Кандидат C лучше других решил исходную задачу. Он точнее и полнее."
                }
                """,
                "deepseek-v4-pro",
                "stop",
                new LlmMetrics(
                        300,
                        new LlmUsage(100, 100, 200, 0, 100, 0),
                        null
                )
        );
    }

    private static final class RecordingDeepSeekClient implements LlmProviderClient {
        private final ArrayDeque<LlmResult> responses;
        private final List<LlmCompletionRequest> requests = new ArrayList<>();

        private RecordingDeepSeekClient(List<LlmResult> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public LlmProvider provider() {
            return LlmProvider.DEEPSEEK;
        }

        @Override
        public LlmResult complete(LlmCompletionRequest request) {
            requests.add(request);
            return responses.removeFirst();
        }
    }
}
