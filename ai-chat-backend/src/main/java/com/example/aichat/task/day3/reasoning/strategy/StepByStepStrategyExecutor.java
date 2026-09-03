package com.example.aichat.task.day3.reasoning.strategy;

import com.example.aichat.task.day3.reasoning.enums.ReasoningStrategy;
import com.example.aichat.task.day3.reasoning.model.ReasoningExperimentContext;
import com.example.aichat.task.day3.reasoning.model.ReasoningStrategyResult;
import com.example.aichat.common.llm.LlmClient;
import org.springframework.stereotype.Component;

import static com.example.aichat.common.profile.service.AgentLlmRequestFactory.create;

/** Executes the StepByStep reasoning strategy for Day 3. */
@Component
public class StepByStepStrategyExecutor implements ReasoningStrategyExecutor {

    private final LlmClient llmClient;

    public StepByStepStrategyExecutor(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public ReasoningStrategy strategy() {
        return ReasoningStrategy.STEP_BY_STEP;
    }

    @Override
    public ReasoningStrategyResult execute(ReasoningExperimentContext context) {
        var systemPrompt = StrategySupport.withInstruction(
                context.profile().systemPrompt(),
                context.config().stepByStep().instruction()
        );
        var result = llmClient.complete(create(
                context.profile(),
                StrategySupport.textMode(
                        "step-by-step",
                        context.config().stepByStep().maxTokens()
                ),
                StrategySupport.messages(systemPrompt, context.task())
        ));
        return ReasoningStrategyResult.answer(strategy(), result);
    }
}
