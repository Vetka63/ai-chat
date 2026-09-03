package com.example.aichat.task.day2.recipe;

import com.example.aichat.common.inputpolicy.InputPolicyRegistry;
import com.example.aichat.common.inputpolicy.StandardInputPolicy;
import com.example.aichat.common.llm.deepseek.DeepSeekClient;
import com.example.aichat.common.outputpolicy.OutputPolicyRegistry;
import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.common.validator.RequestGuardRegistry;
import com.example.aichat.config.ChatProperties;
import com.example.aichat.config.enums.Mode;
import com.example.aichat.task.chat.model.ChatCommand;
import com.example.aichat.task.chat.service.ChatService;
import com.example.aichat.task.day2.recipe.outputpolicy.RecipeJsonOutputPolicy;
import com.example.aichat.task.day2.recipe.outputpolicy.dto.RecipePayload;
import com.example.aichat.task.day2.recipe.validator.RecipeIntentRequestGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class Day2RecipeFlowTest {

    @Test
    void validatesRecipeIntentAndReturnsStructuredRecipe() throws Exception {
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
        var profiles = new AgentRegistry(
                new PathMatchingResourcePatternResolver(),
                properties
        );
        var restClient = builder
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-key")
                .build();
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var classifierResponse = objectMapper.writeValueAsString(Map.of(
                "model", "deepseek-v4-flash",
                "choices", List.of(Map.of(
                        "message", Map.of(
                                "role", "assistant",
                                "content", "[[ALLOW_RECIPE]]"
                        ),
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
                profiles,
                new InputPolicyRegistry(List.of(new StandardInputPolicy())),
                new RequestGuardRegistry(List.of(
                        new RecipeIntentRequestGuard(llmClient, objectMapper)
                )),
                llmClient,
                new OutputPolicyRegistry(List.of(
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

        var response = service.reply(new ChatCommand(
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
