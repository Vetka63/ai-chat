package com.example.aichat.experiment.strategy;

import com.example.aichat.enums.ReasoningStrategy;
import com.example.aichat.experiment.ReasoningExperimentContext;
import com.example.aichat.experiment.ReasoningStrategyExecutor;
import com.example.aichat.experiment.ReasoningStrategyResult;
import com.example.aichat.llm.LlmClient;
import org.springframework.stereotype.Component;

@Component
public class StepByStepStrategyExecutor implements ReasoningStrategyExecutor {

    private static final String INSTRUCTION = """
            Решай пошагово. Покажи ключевые шаги решения, проверь полученный результат,
            затем отдельно и однозначно сформулируй итоговый ответ.
            """;

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
                INSTRUCTION
        );
        var result = llmClient.complete(
                context.profile(),
                StrategySupport.textMode("step-by-step", 1_800),
                StrategySupport.messages(systemPrompt, context.task())
        );
        return ReasoningStrategyResult.answer(strategy(), result);
    }
}
