package com.example.aichat.experiment.strategy;

import com.example.aichat.enums.ReasoningStrategy;
import com.example.aichat.experiment.ReasoningExperimentContext;
import com.example.aichat.experiment.ReasoningStrategyExecutor;
import com.example.aichat.experiment.ReasoningStrategyResult;
import com.example.aichat.llm.LlmClient;
import org.springframework.stereotype.Component;

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
        var result = llmClient.complete(
                context.profile(),
                StrategySupport.textMode("direct", 1_800),
                StrategySupport.messages(context.profile().systemPrompt(), context.task())
        );
        return ReasoningStrategyResult.answer(strategy(), result);
    }
}
