package com.example.aichat.common.inputpolicy;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Индексирует реализации input-policy по типу и обеспечивает немедленную проверку конфигурации. */
@Component
public class InputPolicyRegistry {

    private final Map<String, InputPolicy> policies;

    public InputPolicyRegistry(List<InputPolicy> policies) {
        Map<String, InputPolicy> indexed = new LinkedHashMap<>();
        for (var policy : policies) {
            if (indexed.putIfAbsent(policy.type(), policy) != null) {
                throw new IllegalStateException("Duplicate input policy: " + policy.type());
            }
        }
        this.policies = Map.copyOf(indexed);
    }

    /** Возвращает зарегистрированную политику или отклоняет некорректную конфигурацию профиля. */
    public InputPolicy get(String type) {
        var policy = policies.get(type);
        if (policy == null) {
            throw new IllegalStateException("Unknown input policy: " + type);
        }
        return policy;
    }

    /** Проверяет при запуске приложения, что указанная политика зарегистрирована. */
    public void requireRegistered(String type) {
        get(type);
    }
}
