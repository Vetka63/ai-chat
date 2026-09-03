package com.example.aichat.task.day2.responseformat.outputpolicy;

import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.task.day2.responseformat.outputpolicy.dto.AnswerPayload;
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
                AnswerPayload.class
        );
    }
}
