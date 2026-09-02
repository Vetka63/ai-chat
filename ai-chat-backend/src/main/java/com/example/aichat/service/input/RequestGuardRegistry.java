package com.example.aichat.service.input;

import com.example.aichat.agent.AgentProfile;
import com.example.aichat.llm.LlmMessage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RequestGuardRegistry {

    private final Map<String, RequestGuard> guards;

    public RequestGuardRegistry(List<RequestGuard> guards) {
        Map<String, RequestGuard> indexed = new LinkedHashMap<>();
        for (var guard : guards) {
            if (indexed.putIfAbsent(guard.type(), guard) != null) {
                throw new IllegalStateException("Duplicate request guard: " + guard.type());
            }
        }
        this.guards = Map.copyOf(indexed);
    }

    public void validate(AgentProfile profile, List<LlmMessage> validatedMessages) {
        var config = profile.requestGuard();
        if (config == null) {
            return;
        }
        var guard = guards.get(config.type());
        if (guard == null) {
            throw new IllegalStateException("Unknown request guard: " + config.type());
        }
        guard.validate(profile, config, validatedMessages);
    }
}
