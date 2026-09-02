package com.example.aichat.service;

import com.example.aichat.llm.LlmResult;
import com.example.aichat.service.output.AnswerJsonOutputPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerJsonOutputPolicyTest {

    private final AnswerJsonOutputPolicy policy = new AnswerJsonOutputPolicy(
            new ObjectMapper().findAndRegisterModules()
    );

    @Test
    void acceptsAnswerWithinConfiguredLength() {
        var result = policy.apply(new LlmResult(
                "{\"answer\":\"Краткий корректный ответ\"}",
                "test-model",
                "stop"
        ));

        assertThat(result.reply()).isEqualTo("Краткий корректный ответ");
    }

    @Test
    void truncatesAnswerThatExceedsConfiguredLengthAndKeepsValidStructure() {
        var oversizedAnswer = "а".repeat(501);

        var result = policy.apply(new LlmResult(
                "{\"answer\":\"" + oversizedAnswer + "\"}",
                "test-model",
                "stop"
        ));

        assertThat(result.reply())
                .hasSize(500)
                .endsWith("...");
        assertThat(result.structuredReply()).isInstanceOf(
                AnswerJsonOutputPolicy.AnswerPayload.class
        );
    }
}
