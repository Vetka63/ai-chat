package com.example.aichat.task.day5.modelcomparison.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.HashSet;
import java.util.List;

/** Содержит и проверяет конфигурацию эксперимента по сравнению моделей. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelComparisonConfig(
        List<ModelVariantConfig> models,
        Double temperature,
        Double topP,
        Integer maxTokens,
        ModelComparisonJudgeConfig judge
) {
    public ModelComparisonConfig {
        models = models == null ? List.of() : List.copyOf(models);
    }

    public void validate(String profileId) {
        if (models.size() != 3) {
            throw new IllegalArgumentException("Exactly three model variants are required: " + profileId);
        }
        if (temperature == null || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("Invalid model comparison temperature: " + profileId);
        }
        if (topP == null || topP <= 0 || topP > 1) {
            throw new IllegalArgumentException("Invalid model comparison top_p: " + profileId);
        }
        if (maxTokens == null || maxTokens < 1 || maxTokens > 8_000) {
            throw new IllegalArgumentException("Invalid model comparison max_tokens: " + profileId);
        }
        if (judge == null) {
            throw new IllegalArgumentException("Missing model comparison judge config: " + profileId);
        }
        judge.validate(profileId);

        var ids = new HashSet<String>();
        var providerModels = new HashSet<String>();
        for (var model : models) {
            if (model == null) {
                throw new IllegalArgumentException("Model variant must not be null: " + profileId);
            }
            model.validate(profileId);
            if (!ids.add(model.id())) {
                throw new IllegalArgumentException("Duplicate model variant id: " + model.id());
            }
            var providerModel = model.provider().id() + ":" + model.model();
            if (!providerModels.add(providerModel)) {
                throw new IllegalArgumentException("Duplicate provider model: " + providerModel);
            }
        }
        if (models.stream().noneMatch(model -> model.provider() == com.example.aichat.common.llm.enums.LlmProvider.DEEPSEEK
                && model.model().equals(judge.llm().model()))) {
            throw new IllegalArgumentException("Judge pricing model is not configured: " + profileId);
        }
    }
}
