package com.example.aichat.task.day3.reasoning.strategy;

import com.example.aichat.task.day3.reasoning.enums.ReasoningStrategy;
import com.example.aichat.task.day3.reasoning.model.ReasoningExperimentContext;
import com.example.aichat.task.day3.reasoning.model.ReasoningStrategyResult;
import com.example.aichat.common.llm.LlmClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import static com.example.aichat.common.profile.service.AgentLlmRequestFactory.create;

/** Сначала создаёт метапромпт, а затем использует его для решения задачи. */
@Component
public class MetaPromptStrategyExecutor implements ReasoningStrategyExecutor {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public MetaPromptStrategyExecutor(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReasoningStrategy strategy() {
        return ReasoningStrategy.META_PROMPT;
    }

    @Override
    public ReasoningStrategyResult execute(ReasoningExperimentContext context) {
        var config = context.config().metaPrompt();
        var builderSystemPrompt = StrategySupport.withInstruction(
                context.profile().systemPrompt(),
                config.builderInstruction()
        );
        var promptResult = llmClient.complete(create(
                context.profile(),
                StrategySupport.jsonMode("meta-prompt-builder", config.builderMaxTokens()),
                StrategySupport.messages(builderSystemPrompt, context.task())
        ));
        var generatedPrompt = readPrompt(
                promptResult.content(),
                config.maxGeneratedPromptLength()
        );

        var solverTask = """
                Следуй приведённому ниже промпту и реши исходную задачу.

                <generated_prompt>
                %s
                </generated_prompt>

                <original_task>
                %s
                </original_task>
                """.formatted(generatedPrompt, context.task());
        var answerResult = llmClient.complete(create(
                context.profile(),
                StrategySupport.textMode("meta-prompt-solver", config.solverMaxTokens()),
                StrategySupport.messages(
                        StrategySupport.withInstruction(
                                context.profile().systemPrompt(),
                                config.solverInstruction()
                        ),
                        solverTask
                )
        ));

        return ReasoningStrategyResult.success(
                strategy(),
                answerResult.content(),
                generatedPrompt,
                null,
                null,
                null,
                null,
                answerResult,
                java.util.List.of(promptResult, answerResult)
        );
    }

    private String readPrompt(String content, int maxGeneratedPromptLength) {
        try {
            var payload = objectMapper.readValue(content, PromptPayload.class);
            if (!StringUtils.hasText(payload.prompt())) {
                throw invalidPrompt();
            }
            var prompt = payload.prompt().trim();
            if (prompt.length() > maxGeneratedPromptLength) {
                throw invalidPrompt();
            }
            return prompt;
        } catch (JsonProcessingException exception) {
            throw invalidPrompt();
        }
    }

    private static ResponseStatusException invalidPrompt() {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "LLM returned an invalid generated prompt"
        );
    }

    private record PromptPayload(String prompt) {
    }
}
