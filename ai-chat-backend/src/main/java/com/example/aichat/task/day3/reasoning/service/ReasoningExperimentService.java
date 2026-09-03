package com.example.aichat.task.day3.reasoning.service;

import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.task.day3.reasoning.config.ReasoningConfigProvider;
import com.example.aichat.task.day3.reasoning.model.ExperimentMetrics;
import com.example.aichat.task.day3.reasoning.model.ReasoningExperiment;
import com.example.aichat.task.day3.reasoning.model.ReasoningExperimentContext;
import com.example.aichat.task.day3.reasoning.model.ReasoningStrategyResult;
import com.example.aichat.task.day3.reasoning.strategy.ReasoningStrategyExecutor;
import com.example.aichat.task.day3.reasoning.enums.ReasoningStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Coordinates the application workflow implemented by ReasoningExperimentService. */
@Service
public class ReasoningExperimentService {

    public static final String DEFAULT_PROFILE_ID = "day3-reasoning";
    private static final Logger log = LoggerFactory.getLogger(ReasoningExperimentService.class);

    private final AgentRegistry agentRegistry;
    private final ReasoningConfigProvider configProvider;
    private final Map<ReasoningStrategy, ReasoningStrategyExecutor> executors;

    public ReasoningExperimentService(
            AgentRegistry agentRegistry,
            ReasoningConfigProvider configProvider,
            List<ReasoningStrategyExecutor> executors
    ) {
        this.agentRegistry = agentRegistry;
        this.configProvider = configProvider;
        Map<ReasoningStrategy, ReasoningStrategyExecutor> indexed =
                new EnumMap<>(ReasoningStrategy.class);
        for (var executor : executors) {
            if (indexed.putIfAbsent(executor.strategy(), executor) != null) {
                throw new IllegalStateException(
                        "Duplicate reasoning strategy executor: " + executor.strategy()
                );
            }
        }
        for (var strategy : ReasoningStrategy.values()) {
            if (!indexed.containsKey(strategy)) {
                throw new IllegalStateException(
                        "Missing reasoning strategy executor: " + strategy
                );
            }
        }
        this.executors = Map.copyOf(indexed);
    }

    public ReasoningExperiment run(
            String requestedProfileId,
            String rawTask,
            List<String> requestedStrategies
    ) {
        var profileId = StringUtils.hasText(requestedProfileId)
                ? requestedProfileId.trim()
                : DEFAULT_PROFILE_ID;
        var profile = agentRegistry.get(profileId);
        if (!ReasoningConfigProvider.EXPERIENCE_TYPE.equals(profile.experienceType())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Profile does not support reasoning experiments: " + profileId
            );
        }

        var task = rawTask.trim();
        if (task.length() > profile.inputPolicy().maxMessageLength()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Task must not exceed " + profile.inputPolicy().maxMessageLength() + " characters"
            );
        }
        var strategies = resolveStrategies(requestedStrategies);
        var experimentId = UUID.randomUUID().toString();
        var context = new ReasoningExperimentContext(profile, configProvider.get(profile), task);
        log.info(
                "Reasoning experiment started: experimentId={}, profile={}, strategies={}, taskLength={}",
                experimentId,
                profile.id(),
                strategies.stream().map(ReasoningStrategy::id).toList(),
                task.length()
        );
        var experimentStartedAt = System.nanoTime();

        List<Callable<ReasoningStrategyResult>> calls = strategies.stream()
                .<Callable<ReasoningStrategyResult>>map(strategy ->
                        () -> executeSafely(experimentId, strategy, context))
                .toList();
        List<ReasoningStrategyResult> results;
        try (var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            results = executorService.invokeAll(calls).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new IllegalStateException(
                                    "Reasoning strategy task could not be collected",
                                    exception
                            );
                        }
                    })
                    .toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Reasoning experiment was interrupted"
            );
        }

        var completed = results.stream().filter(result -> "success".equals(result.status())).count();
        var elapsedMs = elapsedMillis(experimentStartedAt);
        var metrics = ExperimentMetrics.combine(
                results.stream().map(ReasoningStrategyResult::metrics).toList()
        ).withElapsedMs(elapsedMs);
        log.info(
                "Reasoning experiment completed: experimentId={}, completed={}, failed={}, elapsedMs={}, apiCalls={}, totalTokens={}, estimatedCostUsd={}",
                experimentId,
                completed,
                results.size() - completed,
                elapsedMs,
                metrics.apiCalls(),
                metrics.totalTokens(),
                metrics.estimatedCostUsd()
        );
        return new ReasoningExperiment(experimentId, profile.id(), task, results, metrics);
    }

    private ReasoningStrategyResult executeSafely(
            String experimentId,
            ReasoningStrategy strategy,
            ReasoningExperimentContext context
    ) {
        var startedAt = System.nanoTime();
        try {
            return executors.get(strategy).execute(context)
                    .withElapsedMs(elapsedMillis(startedAt));
        } catch (Exception exception) {
            log.error(
                    "Reasoning strategy failed: experimentId={}, strategy={}, error={}",
                    experimentId,
                    strategy.id(),
                    exception.getMessage(),
                    exception
            );
            return ReasoningStrategyResult.failure(strategy, safeMessage(exception));
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static List<ReasoningStrategy> resolveStrategies(List<String> requestedStrategies) {
        if (requestedStrategies == null || requestedStrategies.isEmpty()) {
            return List.of(ReasoningStrategy.values());
        }
        try {
            Set<ReasoningStrategy> unique = new LinkedHashSet<>();
            for (var id : requestedStrategies) {
                if (!unique.add(ReasoningStrategy.fromId(id))) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Reasoning strategies must not contain duplicates"
                    );
                }
            }
            return List.copyOf(unique);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private static String safeMessage(Exception exception) {
        if (exception instanceof ResponseStatusException statusException
                && StringUtils.hasText(statusException.getReason())) {
            return statusException.getReason();
        }
        return "Не удалось получить ответ для этого способа. Попробуйте повторить.";
    }
}
