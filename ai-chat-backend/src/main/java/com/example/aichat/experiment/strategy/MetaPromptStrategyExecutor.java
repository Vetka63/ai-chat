package com.example.aichat.experiment.strategy;

import com.example.aichat.enums.ReasoningStrategy;
import com.example.aichat.experiment.ReasoningExperimentContext;
import com.example.aichat.experiment.ReasoningStrategyExecutor;
import com.example.aichat.experiment.ReasoningStrategyResult;
import com.example.aichat.llm.LlmClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MetaPromptStrategyExecutor implements ReasoningStrategyExecutor {

    private static final int MAX_GENERATED_PROMPT_LENGTH = 6_000;
    private static final String PROMPT_BUILDER_INSTRUCTION = """
            Сначала составь сильный самостоятельный промпт, который поможет другой языковой
            модели точно решить задачу пользователя. Сейчас не решай саму задачу.
            Верни только JSON без Markdown по схеме {"prompt":"непустой промпт"}.
            Заверши ответ сразу после закрывающей фигурной скобки.
            """;

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
        var builderSystemPrompt = StrategySupport.withInstruction(
                context.profile().systemPrompt(),
                PROMPT_BUILDER_INSTRUCTION
        );
        var promptResult = llmClient.complete(
                context.profile(),
                StrategySupport.jsonMode("meta-prompt-builder", 600),
                StrategySupport.messages(builderSystemPrompt, context.task())
        );
        var generatedPrompt = readPrompt(promptResult.content());

        var solverTask = """
                Следуй приведённому ниже промпту и реши исходную задачу.

                <generated_prompt>
                %s
                </generated_prompt>

                <original_task>
                %s
                </original_task>
                """.formatted(generatedPrompt, context.task());
        var answerResult = llmClient.complete(
                context.profile(),
                StrategySupport.textMode("meta-prompt-solver", 1_800),
                StrategySupport.messages(context.profile().systemPrompt(), solverTask)
        );

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

    private String readPrompt(String content) {
        try {
            var payload = objectMapper.readValue(content, PromptPayload.class);
            if (!StringUtils.hasText(payload.prompt())) {
                throw invalidPrompt();
            }
            var prompt = payload.prompt().trim();
            if (prompt.length() > MAX_GENERATED_PROMPT_LENGTH) {
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
