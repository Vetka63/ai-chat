package com.example.aichat.common.llm.mistral;

import com.example.aichat.common.llm.enums.LlmProvider;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.config.ChatProperties;
import com.example.aichat.config.MistralProperties;
import com.example.aichat.config.enums.Mode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MistralClientTest {

    @Test
    void sendsCompatibleRequestAndNormalizesUsage() {
        var builder = RestClient.builder().baseUrl("https://mistral.example/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new MistralClient(
                new MistralProperties("https://mistral.example/v1", "test-key"),
                chatProperties(),
                builder.build(),
                new ObjectMapper().findAndRegisterModules()
        );
        server.expect(content().json("""
                        {
                          "model":"ministral-3b-2512",
                          "messages":[
                            {"role":"system","content":"System"},
                            {"role":"user","content":"Task"}
                          ],
                          "temperature":0.0,
                          "top_p":1.0,
                          "max_tokens":1200
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "model":"ministral-3b-2512",
                          "choices":[{
                            "message":{"role":"assistant","content":"Ответ Mistral"},
                            "finish_reason":"stop"
                          }],
                          "usage":{
                            "prompt_tokens":100,
                            "completion_tokens":20,
                            "total_tokens":120,
                            "prompt_tokens_details":{"cached_tokens":25}
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.complete(new LlmCompletionRequest(
                "day5-model-comparison",
                "model-comparison-weak",
                "ministral-3b-2512",
                null,
                null,
                0.0,
                1.0,
                1200,
                null,
                null,
                List.of(new LlmMessage("system", "System"), new LlmMessage("user", "Task"))
        ));

        assertThat(client.provider()).isEqualTo(LlmProvider.MISTRAL);
        assertThat(result.content()).isEqualTo("Ответ Mistral");
        assertThat(result.metrics().usage().promptCacheHitTokens()).isEqualTo(25);
        assertThat(result.metrics().usage().promptCacheMissTokens()).isEqualTo(75);
        assertThat(result.metrics().usage().totalTokens()).isEqualTo(120);
        assertThat(result.metrics().estimatedCostUsd()).isNull();
        server.verify();
    }

    private static ChatProperties chatProperties() {
        return new ChatProperties(
                Mode.LLM,
                "https://deepseek.example",
                "deepseek-key",
                "deepseek-v4-flash",
                "classpath*:agents/*.yml",
                "general",
                false,
                ChatProperties.Pricing.free(),
                List.of("http://localhost:5173")
        );
    }
}
