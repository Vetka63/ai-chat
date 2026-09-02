package com.example.aichat.agent;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentProfile(
        String id,
        String name,
        String description,
        int version,
        boolean enabled,
        String experienceType,
        String systemPrompt,
        InputPolicyConfig inputPolicy,
        RequestGuardConfig requestGuard,
        DeepSeekConfig deepseek,
        ReasoningExperimentConfig reasoningExperiment,
        String defaultResponseMode,
        List<ResponseModeConfig> responseModes
) {
    private static final Set<String> ALLOWED_HISTORY_ROLES = Set.of("user", "assistant");

    public AgentProfile {
        requireText(id, "id");
        if (!id.matches("^[a-z][a-z0-9-]{1,63}$")) {
            throw new IllegalArgumentException("Agent id has an invalid format: " + id);
        }
        requireText(name, "name");
        requireText(description, "description");
        experienceType = experienceType == null || experienceType.isBlank()
                ? "chat"
                : experienceType.trim();
        if (!experienceType.matches("^[a-z][a-z0-9-]{1,63}$")) {
            throw new IllegalArgumentException("Experience type has an invalid format: " + id);
        }
        requireText(systemPrompt, "system_prompt");
        requireText(defaultResponseMode, "default_response_mode");
        if (version < 1) {
            throw new IllegalArgumentException("Agent version must be positive: " + id);
        }
        if (inputPolicy == null || deepseek == null) {
            throw new IllegalArgumentException("Agent policies must be configured: " + id);
        }
        responseModes = responseModes == null ? List.of() : List.copyOf(responseModes);
        inputPolicy.validate(id);
        if (requestGuard != null) {
            requestGuard.validate(id);
        }
        deepseek.validate(id);
        if ("reasoning-experiment".equals(experienceType)) {
            if (reasoningExperiment == null) {
                throw new IllegalArgumentException(
                        "Reasoning experiment config is required: " + id
                );
            }
            reasoningExperiment.validate(id);
        }
        validateResponseModes(id, defaultResponseMode, responseModes);
    }

    public ResponseModeConfig responseMode(String responseModeId) {
        return responseModes.stream()
                .filter(mode -> mode.id().equals(responseModeId))
                .findFirst()
                .orElse(null);
    }

    private static void validateResponseModes(
            String agentId,
            String defaultMode,
            List<ResponseModeConfig> modes
    ) {
        if (modes.isEmpty()) {
            throw new IllegalArgumentException("Agent must define response modes: " + agentId);
        }
        Set<String> ids = new HashSet<>();
        for (var mode : modes) {
            if (mode == null) {
                throw new IllegalArgumentException("Response mode must not be null: " + agentId);
            }
            mode.validate(agentId);
            if (!ids.add(mode.id())) {
                throw new IllegalArgumentException("Duplicate response mode: " + mode.id());
            }
        }
        if (!ids.contains(defaultMode)) {
            throw new IllegalArgumentException("Default response mode does not exist: " + agentId);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Agent field must not be blank: " + field);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RequestGuardConfig(
            String type,
            String instruction,
            Integer maxTokens
    ) {
        void validate(String agentId) {
            requireText(type, "request_guard.type");
            requireText(instruction, "request_guard.instruction");
            if (!type.matches("^[a-z][a-z0-9-]{1,31}$")) {
                throw new IllegalArgumentException("Invalid request guard type: " + agentId);
            }
            if (maxTokens == null || maxTokens < 1) {
                throw new IllegalArgumentException("Invalid request guard max_tokens: " + agentId);
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InputPolicyConfig(
            int maxMessageLength,
            int maxHistoryMessages,
            List<String> allowedRoles
    ) {
        public InputPolicyConfig {
            allowedRoles = allowedRoles == null ? List.of() : List.copyOf(allowedRoles);
        }

        void validate(String agentId) {
            if (maxMessageLength < 1 || maxHistoryMessages < 0) {
                throw new IllegalArgumentException("Invalid input policy: " + agentId);
            }
            if (allowedRoles.isEmpty() || !ALLOWED_HISTORY_ROLES.containsAll(allowedRoles)) {
                throw new IllegalArgumentException("Invalid history roles: " + agentId);
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeepSeekConfig(
            String model,
            String thinking,
            String reasoningEffort,
            Double temperature,
            Double topP
    ) {
        void validate(String agentId) {
            if (thinking != null && !Set.of("enabled", "disabled").contains(thinking)) {
                throw new IllegalArgumentException("Invalid thinking mode: " + agentId);
            }
            if (reasoningEffort != null
                    && !Set.of("low", "high", "max").contains(reasoningEffort)) {
                throw new IllegalArgumentException("Invalid reasoning effort: " + agentId);
            }
            if (temperature != null && (temperature < 0 || temperature > 2)) {
                throw new IllegalArgumentException("Invalid temperature: " + agentId);
            }
            if (topP != null && (topP < 0 || topP > 1)) {
                throw new IllegalArgumentException("Invalid top_p: " + agentId);
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReasoningExperimentConfig(
            ExpertPanelConfig expertPanel,
            JudgeConfig judge
    ) {
        void validate(String agentId) {
            if (expertPanel == null) {
                throw new IllegalArgumentException(
                        "Expert panel config is required: " + agentId
                );
            }
            expertPanel.validate(agentId);
            if (judge == null) {
                throw new IllegalArgumentException(
                        "Reasoning judge config is required: " + agentId
                );
            }
            judge.validate(agentId);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record JudgeConfig(
            String instruction,
            Integer maxTokens
    ) {
        void validate(String agentId) {
            requireText(instruction, "reasoning_experiment.judge.instruction");
            if (maxTokens == null || maxTokens < 1 || maxTokens > 8_000) {
                throw new IllegalArgumentException(
                        "Invalid reasoning judge max_tokens: " + agentId
                );
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExpertPanelConfig(
            String commonInstruction,
            List<ExpertRoleConfig> roles,
            String synthesisInstruction,
            Integer expertMaxTokens,
            Integer synthesisMaxTokens
    ) {
        public ExpertPanelConfig {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        void validate(String agentId) {
            requireText(commonInstruction, "reasoning_experiment.expert_panel.common_instruction");
            requireText(synthesisInstruction, "reasoning_experiment.expert_panel.synthesis_instruction");
            if (roles.size() < 2 || roles.size() > 6) {
                throw new IllegalArgumentException(
                        "Expert panel must contain from two to six roles: " + agentId
                );
            }
            Set<String> ids = new HashSet<>();
            for (var role : roles) {
                if (role == null) {
                    throw new IllegalArgumentException("Expert role must not be null: " + agentId);
                }
                role.validate(agentId);
                if (!ids.add(role.id())) {
                    throw new IllegalArgumentException("Duplicate expert role: " + role.id());
                }
            }
            validateTokenLimit(expertMaxTokens, "expert_max_tokens", agentId);
            validateTokenLimit(synthesisMaxTokens, "synthesis_max_tokens", agentId);
        }

        private static void validateTokenLimit(Integer value, String field, String agentId) {
            if (value == null || value < 1 || value > 8_000) {
                throw new IllegalArgumentException("Invalid " + field + ": " + agentId);
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExpertRoleConfig(
            String id,
            String name,
            String instruction
    ) {
        void validate(String agentId) {
            requireText(id, "reasoning_experiment.expert_panel.roles.id");
            requireText(name, "reasoning_experiment.expert_panel.roles.name");
            requireText(instruction, "reasoning_experiment.expert_panel.roles.instruction");
            if (!id.matches("^[a-z][a-z0-9-]{1,19}$")) {
                throw new IllegalArgumentException("Invalid expert role id: " + agentId);
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResponseModeConfig(
            String id,
            String name,
            String description,
            String instruction,
            String outputPolicy,
            Integer maxTokens,
            Object stop,
            String responseFormat
    ) {
        void validate(String agentId) {
            requireText(id, "response_modes.id");
            requireText(name, "response_modes.name");
            requireText(description, "response_modes.description");
            requireText(outputPolicy, "response_modes.output_policy");
            if (!id.matches("^[a-z][a-z0-9-]{1,31}$")) {
                throw new IllegalArgumentException("Invalid response mode id: " + agentId);
            }
            if (maxTokens != null && maxTokens < 1) {
                throw new IllegalArgumentException("Invalid max_tokens: " + id);
            }
            if (responseFormat != null && !responseFormat.equals("json_object")) {
                throw new IllegalArgumentException("Invalid response_format: " + id);
            }
            validateStop(id, stop);
        }

        private static void validateStop(String modeId, Object value) {
            if (value == null) {
                return;
            }
            if (value instanceof String stringValue && !stringValue.isBlank()) {
                return;
            }
            if (value instanceof List<?> values
                    && !values.isEmpty()
                    && values.size() <= 4
                    && values.stream().allMatch(item -> item instanceof String text && !text.isBlank())) {
                return;
            }
            throw new IllegalArgumentException("Invalid stop sequence: " + modeId);
        }
    }
}
