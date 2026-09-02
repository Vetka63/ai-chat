package com.example.aichat.experiment.strategy;

import com.example.aichat.agent.AgentProfile.ResponseModeConfig;
import com.example.aichat.llm.LlmMessage;

import java.util.List;

final class StrategySupport {

    private StrategySupport() {
    }

    static ResponseModeConfig textMode(String id, int maxTokens) {
        return mode(id, maxTokens, null);
    }

    static ResponseModeConfig jsonMode(String id, int maxTokens) {
        return mode(id, maxTokens, "json_object");
    }

    static List<LlmMessage> messages(String systemPrompt, String task) {
        return List.of(
                new LlmMessage("system", systemPrompt.trim()),
                new LlmMessage("user", task.trim())
        );
    }

    static String withInstruction(String systemPrompt, String instruction) {
        return systemPrompt.trim() + "\n\nИнструкция для текущего способа решения:\n"
                + instruction.trim();
    }

    private static ResponseModeConfig mode(String id, int maxTokens, String responseFormat) {
        return new ResponseModeConfig(
                id,
                id,
                id,
                null,
                "text",
                maxTokens,
                null,
                responseFormat
        );
    }
}
