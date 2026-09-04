package com.example.aichat.task.day5.modelcomparison.service;

import com.example.aichat.common.llm.LlmProviderRegistry;
import com.example.aichat.common.llm.enums.LlmProvider;
import com.example.aichat.common.llm.model.AggregateLlmMetrics;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.task.day5.modelcomparison.config.ModelComparisonConfigProvider;
import com.example.aichat.task.day5.modelcomparison.model.ModelComparisonExperiment;
import com.example.aichat.task.day5.modelcomparison.model.ModelVariantResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Последовательно выполняет одну задачу на трёх настроенных LLM-моделях. */
@Service
public class ModelComparisonService {

    public static final String DEFAULT_PROFILE_ID = "day5-model-comparison";
    private static final Logger log = LoggerFactory.getLogger(ModelComparisonService.class);

    private final AgentRegistry agentRegistry;
    private final ModelComparisonConfigProvider configProvider;
    private final LlmProviderRegistry providerRegistry;
    private final ModelCostCalculator costCalculator;

    public ModelComparisonService(
            AgentRegistry agentRegistry,
            ModelComparisonConfigProvider configProvider,
            LlmProviderRegistry providerRegistry,
            ModelCostCalculator costCalculator
    ) {
        this.agentRegistry = agentRegistry;
        this.configProvider = configProvider;
        this.providerRegistry = providerRegistry;
        this.costCalculator = costCalculator;
    }

    public ModelComparisonExperiment run(String requestedProfileId, String rawTask) {
        var profileId = StringUtils.hasText(requestedProfileId)
                ? requestedProfileId.trim()
                : DEFAULT_PROFILE_ID;
        var profile = agentRegistry.get(profileId);
        if (!ModelComparisonConfigProvider.EXPERIENCE_TYPE.equals(profile.experienceType())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Profile does not support model comparison: " + profileId
            );
        }
        if (!StringUtils.hasText(rawTask)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task must not be blank");
        }
        var task = rawTask.trim();
        if (task.length() > profile.inputPolicy().maxMessageLength()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Task must not exceed " + profile.inputPolicy().maxMessageLength() + " characters"
            );
        }

        var config = configProvider.get(profile);
        var experimentId = UUID.randomUUID().toString();
        var messages = List.of(
                new LlmMessage("system", profile.systemPrompt().trim()),
                new LlmMessage("user", task)
        );
        List<ModelVariantResult> results = new ArrayList<>();
        List<LlmResult> successfulCalls = new ArrayList<>();
        var startedAt = System.nanoTime();

        log.info(
                "Model comparison started: experimentId={}, profile={}, models={}, taskLength={}",
                experimentId,
                profile.id(),
                config.models().stream()
                        .map(model -> model.provider().id() + ":" + model.model())
                        .toList(),
                task.length()
        );

        for (var variant : config.models()) {
            var variantStartedAt = System.nanoTime();
            try {
                var request = new LlmCompletionRequest(
                        profile.id(),
                        "model-comparison-" + variant.id(),
                        variant.model(),
                        variant.provider() == LlmProvider.DEEPSEEK ? "disabled" : null,
                        null,
                        config.temperature(),
                        config.topP(),
                        config.maxTokens(),
                        null,
                        null,
                        messages
                );
                var rawResult = providerRegistry.get(variant.provider()).complete(request);
                var pricedResult = costCalculator.apply(rawResult, variant.pricing());
                successfulCalls.add(pricedResult);
                results.add(ModelVariantResult.success(
                        variant,
                        pricedResult,
                        elapsedMs(variantStartedAt)
                ));
            } catch (ResponseStatusException exception) {
                log.error(
                        "Model comparison variant failed: experimentId={}, provider={}, model={}, error={}",
                        experimentId,
                        variant.provider().id(),
                        variant.model(),
                        exception.getReason(),
                        exception
                );
                results.add(ModelVariantResult.failure(variant, elapsedMs(variantStartedAt)));
            }
        }

        var metrics = AggregateLlmMetrics
                .from(successfulCalls, elapsedMs(startedAt))
                .withApiCalls(config.models().size());
        log.info(
                "Model comparison completed: experimentId={}, successful={}, failed={}, elapsedMs={}, totalTokens={}, estimatedCostUsd={}",
                experimentId,
                successfulCalls.size(),
                results.size() - successfulCalls.size(),
                metrics.elapsedMs(),
                metrics.totalTokens(),
                metrics.estimatedCostUsd()
        );
        return new ModelComparisonExperiment(
                experimentId,
                profile.id(),
                task,
                results,
                metrics
        );
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
