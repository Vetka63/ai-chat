package com.example.aichat.service.input;

import com.example.aichat.agent.AgentProfile;
import com.example.aichat.agent.AgentProfile.ResponseModeConfig;
import com.example.aichat.api.ChatController.ChatRequest;
import com.example.aichat.llm.LlmMessage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Component
public class InputPolicy {

    public List<LlmMessage> buildMessages(
            AgentProfile profile,
            ResponseModeConfig responseMode,
            ChatRequest request
    ) {
        var policy = profile.inputPolicy();
        var message = request.message().trim();
        if (message.length() > policy.maxMessageLength()) {
            throw badRequest(
                    "Message must not exceed "
                            + policy.maxMessageLength()
                            + " characters"
            );
        }
        if (request.history().size() > policy.maxHistoryMessages()) {
            throw badRequest(
                    "History must not exceed "
                            + policy.maxHistoryMessages()
                            + " messages"
            );
        }

        List<LlmMessage> messages = new ArrayList<>();
        var systemPrompt = profile.systemPrompt().trim();
        if (responseMode.instruction() != null && !responseMode.instruction().isBlank()) {
            systemPrompt += "\n\nТребования к формату текущего ответа:\n"
                    + responseMode.instruction().trim();
        }
        messages.add(new LlmMessage("system", systemPrompt));
        for (var item : request.history()) {
            var role = item.role().name();
            if (!policy.allowedRoles().contains(role)) {
                throw badRequest("History role is not allowed: " + role);
            }
            var content = item.content().trim();
            if (content.length() > policy.maxMessageLength()) {
                throw badRequest(
                        "History message must not exceed "
                                + policy.maxMessageLength()
                                + " characters"
                );
            }
            messages.add(new LlmMessage(role, content));
        }
        messages.add(new LlmMessage("user", message));
        return List.copyOf(messages);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
