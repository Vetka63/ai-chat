package com.example.aichat.common.validator;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.llm.model.LlmMessage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Indexes RequestGuard implementations and provides fail-fast lookup by stable identifier. */
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

    public void requireRegistered(String type) {
        if (!guards.containsKey(type)) {
            throw new IllegalStateException("Unknown request guard: " + type);
        }
    }
}
