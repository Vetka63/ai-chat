package com.example.aichat.common.outputpolicy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Indexes OutputPolicy implementations and provides fail-fast lookup by stable identifier. */
@Component
public class OutputPolicyRegistry {

    private final Map<String, OutputPolicy> policies;

    public OutputPolicyRegistry(List<OutputPolicy> policies) {
        this.policies = policies.stream()
                .collect(Collectors.toUnmodifiableMap(
                        OutputPolicy::type,
                        Function.identity()
                ));
    }

    public OutputPolicy get(String type) {
        var policy = policies.get(type);
        if (policy == null) {
            throw new IllegalStateException("Unknown output policy: " + type);
        }
        return policy;
    }
}
