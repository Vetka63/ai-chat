package com.example.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Хранит адрес и локальный секрет для доступа к Mistral API. */
@ConfigurationProperties(prefix = "app.mistral")
public record MistralProperties(
        String baseUrl,
        String apiKey
) {
}
