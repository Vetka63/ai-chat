package com.example.aichat.task.day5.modelcomparison.service;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelComparisonServiceTest {

    @Test
    void sendsIdenticalTaskSettingsToThreeModelsAndCalculatesTheirCosts() {
        var deepSeek = new CapturingClient(LlmProvider.DEEPSEEK);
        var mistral = new CapturingClient(LlmProvider.MISTRAL);
        var service = service(deepSeek, mistral);

        var experiment = service.run("day5-model-comparison", "  Реши задачу  ");

        assertThat(experiment.task()).isEqualTo("Реши задачу");
        assertThat(experiment.results()).extracting(result -> result.id())
                .containsExactly("weak", "medium", "strong");
        assertThat(experiment.results()).extracting(result -> result.provider())
                .containsExactly("mistral", "deepseek", "deepseek");
        assertThat(mistral.requests).hasSize(1);
        assertThat(deepSeek.requests).hasSize(2);

        var requests = List.of(
                mistral.requests.getFirst(),
                deepSeek.requests.get(0),
                deepSeek.requests.get(1)
        );
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.temperature()).isZero();
            assertThat(request.topP()).isEqualTo(1.0);
            assertThat(request.maxTokens()).isEqualTo(1200);
            assertThat(request.messages()).hasSize(2);
            assertThat(request.messages().get(1).content()).isEqualTo("Реши задачу");
        });
        assertThat(mistral.requests.getFirst().thinking()).isNull();
        assertThat(deepSeek.requests).allSatisfy(request ->
                assertThat(request.thinking()).isEqualTo("disabled"));

        assertThat(experiment.results()).extracting(result -> result.metrics().estimatedCostUsd())
                .containsExactly(
                        new java.math.BigDecimal("0.00001200"),
                        new java.math.BigDecimal("0.00007040"),
                        new java.math.BigDecimal("0.00021120")
                );
        assertThat(experiment.metrics().apiCalls()).isEqualTo(3);
        assertThat(experiment.metrics().totalTokens()).isEqualTo(360);
        assertThat(experiment.metrics().estimatedCostUsd()).isEqualByComparingTo("0.00029360");
    }

    @Test
    void keepsDeepSeekResultsWhenMistralIsUnavailable() {
        var service = service(
                new CapturingClient(LlmProvider.DEEPSEEK),
                new FailingClient(LlmProvider.MISTRAL)
        );

        var experiment = service.run("day5-model-comparison", "Задача");

        assertThat(experiment.results()).extracting(result -> result.status().value())
                .containsExactly("error", "success", "success");
        assertThat(experiment.results().getFirst().error())
                .isEqualTo("Не удалось получить ответ от Слабая модель");
        assertThat(experiment.metrics().apiCalls()).isEqualTo(3);
        assertThat(experiment.metrics().totalTokens()).isEqualTo(240);
    }

    private static ModelComparisonService service(LlmProviderClient... clients) {
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
        var configProvider = new ModelComparisonConfigProvider(
                new ObjectMapper().findAndRegisterModules()
        );
        return new ModelComparisonService(
                registry,
                configProvider,
                new LlmProviderRegistry(List.of(clients)),
                new ModelCostCalculator()
        );
    }

    private static final class CapturingClient implements LlmProviderClient {
        private final LlmProvider provider;
        private final List<LlmCompletionRequest> requests = new ArrayList<>();

        private CapturingClient(LlmProvider provider) {
            this.provider = provider;
        }

        @Override
        public LlmProvider provider() {
            return provider;
        }

        @Override
        public LlmResult complete(LlmCompletionRequest request) {
            requests.add(request);
            return new LlmResult(
                    "Ответ " + request.model(),
                    request.model(),
                    "stop",
                    new LlmMetrics(10, new LlmUsage(100, 20, 120, 0, 100, 0), null)
            );
        }
    }

    private static final class FailingClient implements LlmProviderClient {
        private final LlmProvider provider;

        private FailingClient(LlmProvider provider) {
            this.provider = provider;
        }

        @Override
        public LlmProvider provider() {
            return provider;
        }

        @Override
        public LlmResult complete(LlmCompletionRequest request) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider unavailable");
        }
    }
}
