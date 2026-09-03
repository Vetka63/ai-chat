package com.example.aichat.common.llm.deepseek;

import com.example.aichat.common.profile.service.AgentLlmRequestFactory;
import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.config.ChatProperties;
import com.example.aichat.config.enums.Mode;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.llm.model.LlmRequestOverrides;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeepSeekClientMetricsTest {

    @Test
    void readsDeepSeekUsageAndCalculatesConfiguredCost() {
        var builder = RestClient.builder().baseUrl("https://llm.example");
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new ChatProperties(
                Mode.LLM,
                "https://llm.example",
                "test-key",
                "deepseek-chat",
                "classpath*:agents/*.yml",
                "general",
                false,
                new ChatProperties.Pricing(
                        new BigDecimal("2"),
                        new BigDecimal("1"),
                        new BigDecimal("3")
                ),
                List.of("http://localhost:5173")
        );
        var registry = new AgentRegistry(
                new PathMatchingResourcePatternResolver(),
                properties
        );
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var client = new DeepSeekClient(properties, builder.build(), objectMapper);

        server.expect(request -> {})
                .andRespond(withSuccess("""
                        {
                          "model":"deepseek-chat",
                          "choices":[{
                            "message":{"role":"assistant","content":"Ответ"},
                            "finish_reason":"stop"
                          }],
                          "usage":{
                            "prompt_tokens":100,
                            "completion_tokens":20,
                            "total_tokens":120,
                            "prompt_cache_hit_tokens":40,
                            "prompt_cache_miss_tokens":60,
                            "completion_tokens_details":{"reasoning_tokens":7}
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var profile = registry.get("general");
        var result = client.complete(AgentLlmRequestFactory.create(
                profile,
                profile.responseMode("free"),
                List.of(new LlmMessage("user", "Задача"))
        ));

        assertThat(result.metrics().usage().promptTokens()).isEqualTo(100);
        assertThat(result.metrics().usage().completionTokens()).isEqualTo(20);
        assertThat(result.metrics().usage().reasoningTokens()).isEqualTo(7);
        assertThat(result.metrics().estimatedCostUsd()).isEqualByComparingTo("0.00022000");
        assertThat(result.metrics().durationMs()).isGreaterThanOrEqualTo(0);
        server.verify();
    }

    @Test
    void skipsCostEstimateWhenRequestOverridesThePricedModel() {
        var builder = RestClient.builder().baseUrl("https://llm.example");
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new ChatProperties(
                Mode.LLM,
                "https://llm.example",
                "test-key",
                "deepseek-v4-flash",
                "classpath*:agents/*.yml",
                "general",
                false,
                new ChatProperties.Pricing(
                        new BigDecimal("2"),
                        new BigDecimal("1"),
                        new BigDecimal("3")
                ),
                List.of("http://localhost:5173")
        );
        var registry = new AgentRegistry(
                new PathMatchingResourcePatternResolver(),
                properties
        );
        var client = new DeepSeekClient(
                properties,
                builder.build(),
                new ObjectMapper().findAndRegisterModules()
        );
        server.expect(request -> {})
                .andRespond(withSuccess("""
                        {
                          "model":"deepseek-v4-pro",
                          "choices":[{
                            "message":{"role":"assistant","content":"Оценка"},
                            "finish_reason":"stop"
                          }],
                          "usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}
                        }
                        """, MediaType.APPLICATION_JSON));

        var profile = registry.get("general");
        var result = client.complete(AgentLlmRequestFactory.create(
                profile,
                profile.responseMode("free"),
                List.of(new LlmMessage("user", "Сравни ответы")),
                LlmRequestOverrides.model("deepseek-v4-pro", "disabled", null)
        ));

        assertThat(result.model()).isEqualTo("deepseek-v4-pro");
        assertThat(result.metrics().estimatedCostUsd()).isNull();
        server.verify();
    }
}
