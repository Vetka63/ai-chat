package com.example.aichat.task.chat.service;

import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.task.chat.model.ChatCommand;
import com.example.aichat.config.ChatProperties;
import com.example.aichat.config.enums.Mode;
import com.example.aichat.common.llm.LlmClient;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.inputpolicy.InputPolicyRegistry;
import com.example.aichat.common.inputpolicy.StandardInputPolicy;
import com.example.aichat.common.validator.RequestGuardRegistry;
import com.example.aichat.task.day2.answer.outputpolicy.AnswerJsonOutputPolicy;
import com.example.aichat.task.day2.answer.outputpolicy.dto.AnswerPayload;
import com.example.aichat.common.outputpolicy.OutputPolicyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"length", "stop"})
    void retriesTruncatedOrInvalidStructuredOutput(String firstFinishReason) {
        var properties = new ChatProperties(
                Mode.LLM,
                "https://llm.example/v1",
                "test-key",
                "test-model",
                "classpath*:agents/*.yml",
                "general",
                false,
                ChatProperties.Pricing.free(),
                List.of("http://localhost:5173")
        );
        var registry = new AgentRegistry(
                new PathMatchingResourcePatternResolver(),
                properties
        );
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var calls = new AtomicInteger();
        List<LlmCompletionRequest> usedRequests = new ArrayList<>();
        LlmClient llmClient = request -> {
            usedRequests.add(request);
            if (calls.getAndIncrement() == 0) {
                return new LlmResult("{\"answer\":\"обрезанный ответ", "test-model", firstFinishReason);
            }
            return new LlmResult(
                    "{\"answer\":\"Полный восстановленный ответ\"}",
                    "test-model",
                    "stop"
            );
        };
        var service = new ChatService(
                properties,
                registry,
                new InputPolicyRegistry(List.of(new StandardInputPolicy())),
                new RequestGuardRegistry(List.of()),
                llmClient,
                new OutputPolicyRegistry(List.of(
                        new AnswerJsonOutputPolicy(objectMapper)
                ))
        );

        var response = service.reply(new ChatCommand(
                "Расскажи подробно о борще",
                "general",
                "json",
                List.of()
        ));

        assertThat(calls).hasValue(2);
        assertThat(usedRequests).hasSize(2);
        assertThat(usedRequests.get(0).maxTokens()).isEqualTo(300);
        assertThat(usedRequests.get(1).operation()).isEqualTo("json-retry");
        assertThat(usedRequests.get(1).maxTokens()).isEqualTo(1000);
        assertThat(usedRequests.get(1).messages().getFirst().content())
                .contains("повторная попытка");
        assertThat(response.reply()).isEqualTo("Полный восстановленный ответ");
        assertThat(response.structuredReply()).isInstanceOf(AnswerPayload.class);
        assertThat(response.meta().finishReason()).isEqualTo("stop");
        assertThat(response.meta().maxTokens()).isEqualTo(1000);
    }

}
