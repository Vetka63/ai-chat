package com.example.aichat.common.outputpolicy;

import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.outputpolicy.OutputPolicy;
import com.example.aichat.common.outputpolicy.model.OutputPolicyResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Applies the configurable processing rule represented by TextOutputPolicy. */
@Component
public class TextOutputPolicy implements OutputPolicy {

    private static final List<String> FALLBACK_REPLIES = List.of(
            "Интересная мысль. Расскажите об этом немного подробнее.",
            "Я получил ваше сообщение — fallback-режим работает.",
            "Хороший вопрос! Подключите DeepSeek, чтобы получить содержательный ответ.",
            "Сейчас отвечает тестовый помощник. Связь с backend установлена.",
            "Принято. В боевом режиме этот запрос будет передан языковой модели."
    );

    @Override
    public String type() {
        return "text";
    }

    @Override
    public OutputPolicyResult apply(LlmResult result) {
        if (!StringUtils.hasText(result.content())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "LLM provider returned an invalid response"
            );
        }
        return new OutputPolicyResult(result.content().trim(), null);
    }

    @Override
    public OutputPolicyResult fallback() {
        return new OutputPolicyResult(
                FALLBACK_REPLIES.get(ThreadLocalRandom.current().nextInt(FALLBACK_REPLIES.size())),
                null
        );
    }
}
