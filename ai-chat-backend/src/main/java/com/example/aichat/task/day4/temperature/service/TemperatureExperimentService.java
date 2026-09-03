package com.example.aichat.task.day4.temperature.service;

import com.example.aichat.common.llm.LlmClient;
import com.example.aichat.common.llm.model.AggregateLlmMetrics;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.llm.model.LlmRequestOverrides;
import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.profile.model.ResponseModeConfig;
import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.task.day4.temperature.config.TemperatureConfigProvider;
import com.example.aichat.task.day4.temperature.config.TemperatureVariantConfig;
import com.example.aichat.task.day4.temperature.enums.TemperatureResultStatus;
import com.example.aichat.task.day4.temperature.model.TemperatureExperiment;
import com.example.aichat.task.day4.temperature.model.TemperatureVariantResult;
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

import static com.example.aichat.common.profile.service.AgentLlmRequestFactory.create;

/** Последовательно выполняет одну задачу с настроенными температурами DeepSeek. */
@Service
public class TemperatureExperimentService {

    public static final String DEFAULT_PROFILE_ID = "day4-temperature";
    private static final Logger log = LoggerFactory.getLogger(TemperatureExperimentService.class);

    private final AgentRegistry agentRegistry;
    private final TemperatureConfigProvider configProvider;
    private final LlmClient llmClient;

    public TemperatureExperimentService(
            AgentRegistry agentRegistry,
            TemperatureConfigProvider configProvider,
            LlmClient llmClient
    ) {
        this.agentRegistry = agentRegistry;
        this.configProvider = configProvider;
        this.llmClient = llmClient;
    }

    public TemperatureExperiment run(String requestedProfileId, String rawTask) {
        var profileId = StringUtils.hasText(requestedProfileId)
                ? requestedProfileId.trim()
                : DEFAULT_PROFILE_ID;
        var profile = agentRegistry.get(profileId);
        if (!TemperatureConfigProvider.EXPERIENCE_TYPE.equals(profile.experienceType())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Profile does not support temperature experiments: " + profileId
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
        var mode = textMode(config.maxTokens());
        List<TemperatureVariantResult> results = new ArrayList<>();
        List<LlmResult> successfulCalls = new ArrayList<>();
        var startedAt = System.nanoTime();

        log.info(
                "Temperature experiment started: experimentId={}, profile={}, temperatures={}, taskLength={}",
                experimentId,
                profile.id(),
                config.temperatures().stream().map(TemperatureVariantConfig::value).toList(),
                task.length()
        );

        for (var variant : config.temperatures()) {
            var variantStartedAt = System.nanoTime();
            try {
                var result = llmClient.complete(create(
                        profile,
                        mode,
                        messages,
                        LlmRequestOverrides.temperature(variant.value())
                ));
                successfulCalls.add(result);
                results.add(TemperatureVariantResult.success(
                        variant,
                        result,
                        elapsedMs(variantStartedAt)
                ));
            } catch (ResponseStatusException exception) {
                log.error(
                        "Temperature experiment variant failed: experimentId={}, profile={}, temperature={}, error={}",
                        experimentId,
                        profile.id(),
                        variant.value(),
                        exception.getMessage(),
                        exception
                );
                results.add(TemperatureVariantResult.failure(
                        variant,
                        elapsedMs(variantStartedAt)
                ));
            }
        }

        var metrics = AggregateLlmMetrics
                .from(successfulCalls, elapsedMs(startedAt))
                .withApiCalls(config.temperatures().size());
        log.info(
                "Temperature experiment completed: experimentId={}, completed={}, failed={}, elapsedMs={}, apiCalls={}, totalTokens={}",
                experimentId,
                results.stream().filter(result -> result.status() == TemperatureResultStatus.SUCCESS).count(),
                results.stream().filter(result -> result.status() == TemperatureResultStatus.ERROR).count(),
                metrics.elapsedMs(),
                metrics.apiCalls(),
                metrics.totalTokens()
        );
        return new TemperatureExperiment(
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

    private static ResponseModeConfig textMode(int maxTokens) {
        return new ResponseModeConfig(
                "temperature-experiment",
                "temperature-experiment",
                "temperature-experiment",
                null,
                "text",
                maxTokens,
                null,
                null
        );
    }
}
