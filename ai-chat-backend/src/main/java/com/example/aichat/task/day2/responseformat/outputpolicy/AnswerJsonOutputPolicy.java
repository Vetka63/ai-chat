package com.example.aichat.task.day2.responseformat.outputpolicy;

import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.outputpolicy.OutputPolicy;
import com.example.aichat.task.day2.responseformat.outputpolicy.dto.AnswerPayload;
import com.example.aichat.common.outputpolicy.model.OutputPolicyResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Проверяет и преобразует строгий JSON-ответ общего назначения из задания Дня 2. */
@Component
public class AnswerJsonOutputPolicy implements OutputPolicy {

    private final ObjectMapper objectMapper;

    public AnswerJsonOutputPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public String type() {
        return "answer-json";
    }

    @Override
    public OutputPolicyResult apply(LlmResult result) {
        try {
            var payload = objectMapper.readValue(result.content(), AnswerPayload.class);
            return new OutputPolicyResult(payload.answer(), payload);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw invalidJson(exception);
        }
    }

    @Override
    public OutputPolicyResult fallback() {
        var payload = new AnswerPayload(
                "Fallback-режим работает. Подключите DeepSeek для содержательного ответа."
        );
        return new OutputPolicyResult(payload.answer(), payload);
    }

    @Override
    public boolean supportsStructuredRetry() {
        return true;
    }

    @Override
    public String retryConstraint() {
        return "Значение поля answer должно быть не длиннее 500 символов, "
                + "а весь JSON — не длиннее 650 символов.";
    }

    private static ResponseStatusException invalidJson(Exception cause) {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "LLM returned JSON that does not match the answer schema",
                cause
        );
    }

}
