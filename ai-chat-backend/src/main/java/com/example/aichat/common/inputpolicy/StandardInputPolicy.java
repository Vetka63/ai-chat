package com.example.aichat.common.inputpolicy;

import com.example.aichat.common.inputpolicy.model.InputHistoryMessage;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.ResponseModeConfig;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Стандартная входная политика: проверяет ограничения длины и ролей,
 * объединяет системный промпт профиля, инструкцию режима, историю и последнее сообщение.
 */
@Component
public class StandardInputPolicy implements InputPolicy {

    public static final String TYPE = "standard";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<LlmMessage> buildMessages(
            AgentProfile profile,
            ResponseModeConfig responseMode,
            String rawMessage,
            List<InputHistoryMessage> history
    ) {
        var policy = profile.inputPolicy();
        var message = rawMessage.trim();
        if (message.length() > policy.maxMessageLength()) {
            throw badRequest("Message must not exceed " + policy.maxMessageLength() + " characters");
        }
        if (history.size() > policy.maxHistoryMessages()) {
            throw badRequest("History must not exceed " + policy.maxHistoryMessages() + " messages");
        }

        List<LlmMessage> messages = new ArrayList<>();
        var systemPrompt = profile.systemPrompt().trim();
        if (responseMode.instruction() != null && !responseMode.instruction().isBlank()) {
            systemPrompt += "\n\nТребования к формату текущего ответа:\n"
                    + responseMode.instruction().trim();
        }
        messages.add(new LlmMessage("system", systemPrompt));
        for (var item : history) {
            var role = item.role().name();
            if (!policy.allowedRoles().contains(role)) {
                throw badRequest("History role is not allowed: " + role);
            }
            var content = item.content().trim();
            if (content.length() > policy.maxMessageLength()) {
                throw badRequest(
                        "History message must not exceed " + policy.maxMessageLength() + " characters"
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
