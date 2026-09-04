package com.example.aichat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** Создаёт общий авторизованный HTTP-клиент для адаптеров LLM-провайдеров. */
@Configuration
public class LlmClientConfig {

    @Bean("deepSeekRestClient")
    RestClient deepSeekRestClient(ChatProperties properties) {
        return createClient(properties.baseUrl(), properties.apiKey());
    }

    @Bean("mistralRestClient")
    RestClient mistralRestClient(MistralProperties properties) {
        return createClient(properties.baseUrl(), properties.apiKey());
    }

    private static RestClient createClient(String baseUrl, String apiKey) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(120));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }
}
