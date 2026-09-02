package com.example.aichat.service;

import com.example.aichat.agent.AgentRegistry;
import com.example.aichat.agent.AgentProfile.ResponseModeConfig;
import com.example.aichat.api.ChatController.ChatRequest;
import com.example.aichat.config.ChatProperties;
import com.example.aichat.enums.Mode;
import com.example.aichat.llm.DeepSeekClient;
import com.example.aichat.llm.LlmClient;
import com.example.aichat.llm.LlmResult;
import com.example.aichat.service.input.InputPolicy;
import com.example.aichat.service.input.RecipeIntentRequestGuard;
import com.example.aichat.service.input.RequestGuardRegistry;
import com.example.aichat.service.output.AnswerJsonOutputPolicy;
import com.example.aichat.service.output.AnswerJsonOutputPolicy.AnswerPayload;
import com.example.aichat.service.output.OutputPolicyRegistry;
import com.example.aichat.service.output.RecipeJsonOutputPolicy;
import com.example.aichat.service.output.RecipeJsonOutputPolicy.RecipePayload;
import com.example.aichat.service.output.TextOutputPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChatServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"length", "stop"})
    void retriesTruncatedOrInvalidStructuredOutput(String firstFinishReason) {
        var properties = new ChatProperties(
                Mode.LLM,
                "https://llm.example/v1",
                "test-key",
                "test-model",
                "classpath*:agents/*.yml",
                "general",
                false,
                ChatProperties.Pricing.free(),
                List.of("http://localhost:5173")
        );
        var registry = new AgentRegistry(
                new PathMatchingResourcePatternResolver(),
                properties
        );
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var calls = new AtomicInteger();
        List<ResponseModeConfig> usedModes = new ArrayList<>();
        LlmClient llmClient = (profile, responseMode, messages) -> {
            usedModes.add(responseMode);
            if (calls.getAndIncrement() == 0) {
                return new LlmResult("{\"answer\":\"обрезанный ответ", "test-model", firstFinishReason);
            }
            return new LlmResult(
                    "{\"answer\":\"Полный восстановленный ответ\"}",
                    "test-model",
                    "stop"
            );
        };
        var service = new ChatService(
                properties,
                registry,
                new InputPolicy(),
                new RequestGuardRegistry(List.of()),
                llmClient,
                new OutputPolicyRegistry(List.of(
                        new TextOutputPolicy(),
                        new AnswerJsonOutputPolicy(objectMapper),
                        new RecipeJsonOutputPolicy(objectMapper)
                ))
        );

        var response = service.reply(new ChatRequest(
                "Расскажи подробно о борще",
                "general",
                "json",
                List.of()
        ));

        assertThat(calls).hasValue(2);
        assertThat(usedModes).hasSize(2);
        assertThat(usedModes.get(0).maxTokens()).isEqualTo(300);
        assertThat(usedModes.get(1).id()).isEqualTo("json-retry");
        assertThat(usedModes.get(1).maxTokens()).isEqualTo(1000);
        assertThat(usedModes.get(1).instruction()).contains("повторная попытка");
        assertThat(response.reply()).isEqualTo("Полный восстановленный ответ");
        assertThat(response.structuredReply()).isInstanceOf(AnswerPayload.class);
        assertThat(response.meta().finishReason()).isEqualTo("stop");
        assertThat(response.meta().maxTokens()).isEqualTo(1000);
    }

    @Test
    void appliesJsonModeAndCallsDeepSeekWithResponseControls() throws Exception {
        var builder = RestClient.builder().baseUrl("https://llm.example/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new ChatProperties(
                Mode.LLM,
                "https://llm.example/v1",
                "test-key",
                "test-model",
                "classpath*:agents/*.yml",
                "general",
                false,
                ChatProperties.Pricing.free(),
                List.of("http://localhost:5173")
        );
        var registry = new AgentRegistry(
                new PathMatchingResourcePatternResolver(),
                properties
        );
        var restClient = builder
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-key")
                .build();
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var classifierResult = "[[ALLOW_RECIPE]]";
        var classifierResponse = objectMapper.writeValueAsString(Map.of(
                "model", "deepseek-v4-flash",
                "choices", List.of(Map.of(
                        "message", Map.of("role", "assistant", "content", classifierResult),
                        "finish_reason", "stop"
                ))
        ));
        var recipeJson = """
                {"dishName":"Овощной салат","requiredIngredients":["Помидоры — 2 шт.","Огурцы — 2 шт."],"cookingTime":"15 минут"}
                """.trim();
        var providerResponse = objectMapper.writeValueAsString(Map.of(
                "model", "deepseek-v4-flash",
                "choices", List.of(Map.of(
                        "message", Map.of("role", "assistant", "content", recipeJson),
                        "finish_reason", "stop"
                ))
        ));
        var llmClient = new DeepSeekClient(properties, restClient, objectMapper);
        var service = new ChatService(
                properties,
                registry,
                new InputPolicy(),
                new RequestGuardRegistry(List.of(
                        new RecipeIntentRequestGuard(llmClient, objectMapper)
                )),
                llmClient,
                new OutputPolicyRegistry(List.of(
                        new TextOutputPolicy(),
                        new AnswerJsonOutputPolicy(objectMapper),
                        new RecipeJsonOutputPolicy(objectMapper)
                ))
        );

        server.expect(requestTo("https://llm.example/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().string(containsString("внутренний классификатор")))
                .andExpect(content().json("""
                        {
                          "model": "test-model",
                          "thinking": {"type": "disabled"},
                          "temperature": 0.0,
                          "max_tokens": 20
                        }
                        """, false))
                .andExpect(content().string(not(containsString("response_format"))))
                .andRespond(withSuccess(classifierResponse, MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://llm.example/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().string(containsString("Требования к формату текущего ответа")))
                .andExpect(content().string(containsString("requiredIngredients")))
                .andExpect(content().json("""
                        {
                          "model": "test-model",
                          "thinking": {"type": "disabled"},
                          "temperature": 0.0,
                          "max_tokens": 1000,
                          "response_format": {"type": "json_object"}
                        }
                        """, false))
                .andRespond(withSuccess(providerResponse, MediaType.APPLICATION_JSON));

        var response = service.reply(new ChatRequest(
                "Хочу приготовить салат",
                "recipe",
                "json",
                List.of()
        ));

        assertThat(response.reply()).isEqualTo("Овощной салат");
        assertThat(response.source()).isEqualTo("llm");
        assertThat(response.profileId()).isEqualTo("recipe");
        assertThat(response.responseMode()).isEqualTo("json");
        assertThat(response.structuredReply()).isInstanceOf(RecipePayload.class);
        var payload = (RecipePayload) response.structuredReply();
        assertThat(payload.requiredIngredients()).hasSize(2);
        assertThat(payload.cookingTime()).isEqualTo("15 минут");
        assertThat(response.meta().model()).isEqualTo("deepseek-v4-flash");
        assertThat(response.meta().finishReason()).isEqualTo("stop");
        assertThat(response.meta().maxTokens()).isEqualTo(1000);
        assertThat(response.meta().responseFormat()).isEqualTo("json_object");
        server.verify();
    }
}
