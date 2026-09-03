package com.example.aichat.bootstrap;

import com.example.aichat.common.profile.validator.AgentFeatureConfigValidator;
import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.common.outputpolicy.OutputPolicyRegistry;
import com.example.aichat.common.inputpolicy.InputPolicyRegistry;

import com.example.aichat.common.validator.RequestGuardRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Проверяет межмодульные ссылки профилей после регистрации всех компонентов Spring. */
@Component
public class AgentConfigurationVerifier implements SmartInitializingSingleton {

    private final AgentRegistry agentRegistry;
    private final RequestGuardRegistry requestGuards;
    private final OutputPolicyRegistry outputPolicies;
    private final InputPolicyRegistry inputPolicies;
    private final Map<String, AgentFeatureConfigValidator> featureValidators;

    public AgentConfigurationVerifier(
            AgentRegistry agentRegistry,
            RequestGuardRegistry requestGuards,
            OutputPolicyRegistry outputPolicies,
            InputPolicyRegistry inputPolicies,
            List<AgentFeatureConfigValidator> featureValidators
    ) {
        this.agentRegistry = agentRegistry;
        this.requestGuards = requestGuards;
        this.outputPolicies = outputPolicies;
        this.inputPolicies = inputPolicies;
        Map<String, AgentFeatureConfigValidator> indexed = new LinkedHashMap<>();
        for (var validator : featureValidators) {
            if (indexed.putIfAbsent(validator.experienceType(), validator) != null) {
                throw new IllegalStateException(
                        "Duplicate feature config validator: " + validator.experienceType()
                );
            }
        }
        this.featureValidators = Map.copyOf(indexed);
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (var profile : agentRegistry.all()) {
            inputPolicies.requireRegistered(profile.inputPolicy().type());
            if (profile.requestGuard() != null) {
                requestGuards.requireRegistered(profile.requestGuard().type());
            }
            for (var mode : profile.responseModes()) {
                outputPolicies.get(mode.outputPolicy());
            }
            if (!"chat".equals(profile.experienceType()) || !profile.featureConfig().isEmpty()) {
                var validator = featureValidators.get(profile.experienceType());
                if (validator == null) {
                    throw new IllegalStateException(
                            "Unknown experience type: " + profile.experienceType()
                    );
                }
                validator.validate(profile);
            }
        }
    }
}
