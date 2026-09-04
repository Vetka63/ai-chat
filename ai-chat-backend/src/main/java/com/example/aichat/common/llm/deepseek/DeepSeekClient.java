package com.example.aichat.common.llm.deepseek;

import com.example.aichat.common.llm.LlmClient;
import com.example.aichat.common.llm.LlmProviderClient;
import com.example.aichat.common.llm.enums.LlmProvider;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.llm.model.LlmMetrics;
import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.llm.model.LlmUsage;
import com.example.aichat.config.ChatProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Реализует контракт LLM через совместимый с OpenAI API провайдера DeepSeek. */
@Component
public class DeepSeekClient implements LlmClient, LlmProviderClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000);

    private final ChatProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public DeepSeekClient(
            ChatProperties properties,
            @Qualifier("deepSeekRestClient") RestClient restClient,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.DEEPSEEK;
    }

    @Override
    public LlmResult complete(LlmCompletionRequest command) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "LLM_API_KEY is required when CHAT_MODE=llm"
            );
        }

        var request = new LlmRequest(
                StringUtils.hasText(command.model()) ? command.model() : properties.model(),
                command.messages(),
                command.thinking() == null ? null : new Thinking(command.thinking()),
                command.reasoningEffort(),
                command.temperature(),
                command.topP(),
                command.maxTokens(),
                command.stop(),
                command.responseFormat() == null
                        ? null
                        : new ResponseFormat(command.responseFormat())
        );
        var callId = UUID.randomUUID().toString();
        log.info(
                "DeepSeek request started: callId={}, profile={}, operation={}, model={}, messageCount={}, maxTokens={}, responseFormat={}",
                callId,
                command.profileId(),
                command.operation(),
                request.model(),
                command.messages().size(),
                request.maxTokens(),
                command.responseFormat()
        );
        if (properties.logPayloads()) {
            log.info(
                    "DeepSeek request payload: callId={}, payload={}",
                    callId,
                    toJson(request)
            );
        }

        var startedAt = System.nanoTime();
        try {
            var responseBody = restClient
                    .post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(String.class);

            if (properties.logPayloads()) {
                log.info(
                        "DeepSeek response payload: callId={}, payload={}",
                        callId,
                        responseBody
                );
            }
            var response = parseResponse(responseBody, callId);

            if (response == null
                    || response.choices() == null
                    || response.choices().isEmpty()
                    || response.choices().getFirst().message() == null
                    || !StringUtils.hasText(response.choices().getFirst().message().content())) {
                log.error("DeepSeek returned an incomplete response: callId={}", callId);
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "LLM provider returned an invalid response"
                );
            }

            var choice = response.choices().getFirst();
            var responseModel = StringUtils.hasText(response.model())
                    ? response.model()
                    : request.model();
            var durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            var usage = toUsage(response.usage());
            var estimatedCost = estimateCost(usage, request.model());
            log.info(
                    "DeepSeek request completed: callId={}, profile={}, operation={}, responseModel={}, finishReason={}, contentLength={}, durationMs={}, promptTokens={}, completionTokens={}, totalTokens={}, estimatedCostUsd={}",
                    callId,
                    command.profileId(),
                    command.operation(),
                    responseModel,
                    choice.finishReason(),
                    choice.message().content().length(),
                    durationMs,
                    usage.promptTokens(),
                    usage.completionTokens(),
                    usage.totalTokens(),
                    estimatedCost
            );
            return new LlmResult(
                    choice.message().content().trim(),
                    responseModel,
                    choice.finishReason(),
                    new LlmMetrics(durationMs, usage, estimatedCost)
            );
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.error(
                    "DeepSeek HTTP error: callId={}, status={}, responseBody={}",
                    callId,
                    exception.getStatusCode().value(),
                    exception.getResponseBodyAsString(),
                    exception
            );
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "LLM provider returned an error"
            );
        } catch (RestClientException exception) {
            log.error(
                    "DeepSeek request failed: callId={}, error={}",
                    callId,
                    exception.getMessage(),
                    exception
            );
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "LLM provider is unavailable"
            );
        }
    }

    private LlmUsage toUsage(LlmUsageResponse usage) {
        if (usage == null) {
            return LlmUsage.empty();
        }
        var promptTokens = value(usage.promptTokens());
        var completionTokens = value(usage.completionTokens());
        var totalTokens = value(usage.totalTokens());
        if (totalTokens == 0 && (promptTokens > 0 || completionTokens > 0)) {
            totalTokens = promptTokens + completionTokens;
        }
        return new LlmUsage(
                promptTokens,
                completionTokens,
                totalTokens,
                value(usage.promptCacheHitTokens()),
                value(usage.promptCacheMissTokens()),
                usage.completionTokensDetails() == null
                        ? 0
                        : value(usage.completionTokensDetails().reasoningTokens())
        );
    }

    private BigDecimal estimateCost(LlmUsage usage, String requestModel) {
        var pricing = properties.pricing();
        if (!pricing.configured()) {
            return null;
        }
        if (!properties.model().equals(requestModel)) {
            log.debug(
                    "Cost estimate skipped: request model {} differs from priced default model {}",
                    requestModel,
                    properties.model()
            );
            return null;
        }

        var cacheHitTokens = usage.promptCacheHitTokens();
        var cacheMissTokens = usage.promptCacheMissTokens();
        var classifiedPromptTokens = cacheHitTokens + cacheMissTokens;
        if (classifiedPromptTokens < usage.promptTokens()) {
            cacheMissTokens += usage.promptTokens() - classifiedPromptTokens;
        }

        var inputMissCost = tokenCost(
                cacheMissTokens,
                pricing.promptCacheMissPerMillionUsd()
        );
        var inputHitCost = tokenCost(
                cacheHitTokens,
                pricing.promptCacheHitPerMillionUsd()
        );
        var outputCost = tokenCost(
                usage.completionTokens(),
                pricing.outputPerMillionUsd()
        );
        return inputMissCost.add(inputHitCost).add(outputCost)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal tokenCost(long tokens, BigDecimal pricePerMillion) {
        return BigDecimal.valueOf(tokens)
                .multiply(pricePerMillion)
                .divide(TOKENS_PER_MILLION, 12, RoundingMode.HALF_UP);
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private LlmResponse parseResponse(String responseBody, String callId) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        try {
            return objectMapper.readValue(responseBody, LlmResponse.class);
        } catch (JsonProcessingException exception) {
            log.error(
                    "DeepSeek response JSON could not be parsed: callId={}, error={}",
                    callId,
                    exception.getOriginalMessage(),
                    exception
            );
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "LLM provider returned malformed JSON"
            );
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            log.error("Failed to serialize LLM diagnostic payload", exception);
            return "<serialization failed>";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LlmRequest(
            String model,
            List<LlmMessage> messages,
            Thinking thinking,
            @JsonProperty("reasoning_effort") String reasoningEffort,
            Double temperature,
            @JsonProperty("top_p") Double topP,
            @JsonProperty("max_tokens") Integer maxTokens,
            Object stop,
            @JsonProperty("response_format") ResponseFormat responseFormat
    ) {
    }

    record Thinking(String type) {
    }

    record ResponseFormat(String type) {
    }

    record LlmResponse(
            String model,
            List<LlmChoice> choices,
            LlmUsageResponse usage
    ) {
    }

    record LlmChoice(
            LlmMessage message,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    record LlmUsageResponse(
            @JsonProperty("prompt_tokens") Long promptTokens,
            @JsonProperty("completion_tokens") Long completionTokens,
            @JsonProperty("total_tokens") Long totalTokens,
            @JsonProperty("prompt_cache_hit_tokens") Long promptCacheHitTokens,
            @JsonProperty("prompt_cache_miss_tokens") Long promptCacheMissTokens,
            @JsonProperty("completion_tokens_details") CompletionTokensDetails completionTokensDetails
    ) {
    }

    record CompletionTokensDetails(
            @JsonProperty("reasoning_tokens") Long reasoningTokens
    ) {
    }
}
