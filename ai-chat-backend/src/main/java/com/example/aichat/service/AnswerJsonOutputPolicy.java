package com.example.aichat.service;

import com.example.aichat.llm.LlmResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AnswerJsonOutputPolicy implements OutputPolicy {

    private static final Logger log = LoggerFactory.getLogger(AnswerJsonOutputPolicy.class);
    private static final int MAX_ANSWER_LENGTH = 500;
    private static final String TRUNCATION_SUFFIX = "...";

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

    private static ResponseStatusException invalidJson(Exception cause) {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "LLM returned JSON that does not match the answer schema",
                cause
        );
    }

    public record AnswerPayload(String answer) {
        public AnswerPayload {
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException("answer must not be blank");
            }
            answer = answer.trim();
            if (answer.length() > MAX_ANSWER_LENGTH) {
                log.info(
                        "Truncating answer-json output: originalLength={}, maxLength={}",
                        answer.length(),
                        MAX_ANSWER_LENGTH
                );
                answer = answer.substring(
                        0,
                        MAX_ANSWER_LENGTH - TRUNCATION_SUFFIX.length()
                ).stripTrailing() + TRUNCATION_SUFFIX;
            }
        }
    }
}
