package com.example.javamcpgames.catalog;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Создаёт HTTP-клиент для отдельного mock API; MCP starter не предоставляет его автоматически. */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient.Builder gamesRestClientBuilder() {
        return RestClient.builder();
    }
}
