package com.example.aichat.service;

import com.example.aichat.api.ChatController.ChatResponse;
import com.example.aichat.config.ChatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final List<String> FALLBACK_REPLIES = List.of(
            "Интересная мысль. Расскажите об этом немного подробнее.",
            "Я получил ваше сообщение — fallback-режим работает.",
            "Хороший вопрос! Подключите LLM, чтобы получить содержательный ответ.",
            "Сейчас отвечает тестовый помощник. Связь с backend установлена.",
            "Принято. В боевом режиме этот запрос будет передан языковой модели."
    );

    private final ChatProperties properties;
    private final RestClient llmClient;

    public ChatService(ChatProperties properties, RestClient llmClient) {
        this.properties = properties;
        this.llmClient = llmClient;
    }

    public ChatResponse reply(String message) {
        if (properties.mode() == ChatProperties.Mode.FALLBACK) {
            var index = ThreadLocalRandom.current().nextInt(FALLBACK_REPLIES.size());
            return new ChatResponse(FALLBACK_REPLIES.get(index), "fallback");
        }
        return callLlm(message);
    }

    private ChatResponse callLlm(String message) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "LLM_API_KEY is required when CHAT_MODE=llm");
        }

        var request = new LlmRequest(properties.model(), List.of(new LlmMessage("user", message)));

        try {
            var response = llmClient
                    .post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(LlmResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()
                    || response.choices().getFirst().message() == null
                    || !StringUtils.hasText(response.choices().getFirst().message().content())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "LLM provider returned an empty response");
            }

            return new ChatResponse(response.choices().getFirst().message().content(), "llm");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn("LLM provider request failed: {}", exception.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "LLM provider is unavailable");
        }
    }

    record LlmRequest(String model, List<LlmMessage> messages) {
    }

    record LlmMessage(String role, String content) {
    }

    record LlmResponse(List<LlmChoice> choices) {
    }

    record LlmChoice(LlmMessage message) {
    }
}
