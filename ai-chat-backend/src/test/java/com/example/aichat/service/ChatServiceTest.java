package com.example.aichat.service;

import com.example.aichat.config.ChatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChatServiceTest {

    @Test
    void forwardsTheUserMessageToAnOpenAiCompatibleApi() {
        var builder = RestClient.builder().baseUrl("https://llm.example/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new ChatProperties(
                ChatProperties.Mode.LLM,
                "https://llm.example/v1",
                "test-key",
                "test-model",
                List.of("http://localhost:5173")
        );
        var service = new ChatService(properties, builder
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-key")
                .build());

        server.expect(requestTo("https://llm.example/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().json("""
                        {
                          "model": "test-model",
                          "messages": [{"role": "user", "content": "Hello LLM"}]
                        }
                        """))
                .andRespond(withSuccess("""
                        {"choices": [{"message": {"role": "assistant", "content": "Hello human"}}]}
                        """, MediaType.APPLICATION_JSON));

        var response = service.reply("Hello LLM");

        assertThat(response.reply()).isEqualTo("Hello human");
        assertThat(response.source()).isEqualTo("llm");
        server.verify();
    }
}

