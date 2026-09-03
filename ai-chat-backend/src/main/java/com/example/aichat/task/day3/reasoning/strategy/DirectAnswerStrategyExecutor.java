package com.example.aichat.task.day3.reasoning.strategy;

import com.example.aichat.task.day3.reasoning.enums.ReasoningStrategy;
import com.example.aichat.task.day3.reasoning.model.ReasoningExperimentContext;
import com.example.aichat.task.day3.reasoning.model.ReasoningStrategyResult;
import com.example.aichat.common.llm.LlmClient;
import org.springframework.stereotype.Component;

import static com.example.aichat.common.profile.service.AgentLlmRequestFactory.create;

/** Выполняет стратегию прямого ответа без дополнительных инструкций. */
@Component
public class DirectAnswerStrategyExecutor implements ReasoningStrategyExecutor {

    private final LlmClient llmClient;

    public DirectAnswerStrategyExecutor(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public ReasoningStrategy strategy() {
        return ReasoningStrategy.DIRECT;
    }

    @Override
    public ReasoningStrategyResult execute(ReasoningExperimentContext context) {
        var result = llmClient.complete(create(
                context.profile(),
                StrategySupport.textMode("direct", context.config().direct().maxTokens()),
                StrategySupport.messages(context.profile().systemPrompt(), context.task())
        ));
        return ReasoningStrategyResult.answer(strategy(), result);
    }
}
