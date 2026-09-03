package com.example.aichat.common.inputpolicy;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Indexes input-policy implementations by type and provides fail-fast lookup. */
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

    /** Returns a registered policy or rejects invalid profile configuration. */
    public InputPolicy get(String type) {
        var policy = policies.get(type);
        if (policy == null) {
            throw new IllegalStateException("Unknown input policy: " + type);
        }
        return policy;
    }

    /** Verifies that a policy exists during application startup. */
    public void requireRegistered(String type) {
        get(type);
    }
}
