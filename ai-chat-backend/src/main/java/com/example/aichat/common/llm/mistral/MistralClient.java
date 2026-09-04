package com.example.aichat.common.llm.mistral;

import com.example.aichat.common.llm.LlmProviderClient;
import com.example.aichat.common.llm.enums.LlmProvider;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.llm.model.LlmMetrics;
import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.llm.model.LlmUsage;
import com.example.aichat.config.ChatProperties;
import com.example.aichat.config.MistralProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Адаптирует Mistral Chat Completions API к общему контракту LLM-провайдера. */
@Component
public class MistralClient implements LlmProviderClient {

    private static final Logger log = LoggerFactory.getLogger(MistralClient.class);

    private final MistralProperties properties;
    private final ChatProperties chatProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public MistralClient(
            MistralProperties properties,
            ChatProperties chatProperties,
            @Qualifier("mistralRestClient") RestClient restClient,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.chatProperties = chatProperties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.MISTRAL;
    }

    @Override
    public LlmResult complete(LlmCompletionRequest command) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MISTRAL_API_KEY is required for the selected model"
            );
        }
        if (!StringUtils.hasText(command.model())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mistral model is required");
        }

        var request = new MistralRequest(
                command.model().trim(),
                command.messages(),
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
                "Mistral request started: callId={}, profile={}, operation={}, model={}, messageCount={}, maxTokens={}",
                callId,
                command.profileId(),
                command.operation(),
                request.model(),
                request.messages().size(),
                request.maxTokens()
        );
        if (chatProperties.logPayloads()) {
            log.info("Mistral request payload: callId={}, payload={}", callId, toJson(request));
        }

        var startedAt = System.nanoTime();
        try {
            var responseBody = restClient.post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(String.class);

            if (chatProperties.logPayloads()) {
                log.info("Mistral response payload: callId={}, payload={}", callId, responseBody);
            }
            var response = parseResponse(responseBody, callId);
            if (response == null
                    || response.choices() == null
                    || response.choices().isEmpty()
                    || response.choices().getFirst().message() == null
                    || !StringUtils.hasText(response.choices().getFirst().message().content())) {
                log.error("Mistral returned an incomplete response: callId={}", callId);
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Mistral returned an invalid response"
                );
            }

            var choice = response.choices().getFirst();
            var responseModel = StringUtils.hasText(response.model())
                    ? response.model()
                    : request.model();
            var durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            var usage = toUsage(response.usage());
            log.info(
                    "Mistral request completed: callId={}, profile={}, operation={}, responseModel={}, finishReason={}, durationMs={}, promptTokens={}, completionTokens={}, totalTokens={}",
                    callId,
                    command.profileId(),
                    command.operation(),
                    responseModel,
                    choice.finishReason(),
                    durationMs,
                    usage.promptTokens(),
                    usage.completionTokens(),
                    usage.totalTokens()
            );
            return new LlmResult(
                    choice.message().content().trim(),
                    responseModel,
                    choice.finishReason(),
                    new LlmMetrics(durationMs, usage, null)
            );
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.error(
                    "Mistral HTTP error: callId={}, status={}, responseBody={}",
                    callId,
                    exception.getStatusCode().value(),
                    exception.getResponseBodyAsString(),
                    exception
            );
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Mistral returned an error");
        } catch (RestClientException exception) {
            log.error("Mistral request failed: callId={}, error={}", callId, exception.getMessage(), exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Mistral is unavailable");
        }
    }

    private LlmUsage toUsage(Usage response) {
        if (response == null) {
            return LlmUsage.empty();
        }
        var promptTokens = value(response.promptTokens());
        var completionTokens = value(response.completionTokens());
        var totalTokens = value(response.totalTokens());
        if (totalTokens == 0 && (promptTokens > 0 || completionTokens > 0)) {
            totalTokens = promptTokens + completionTokens;
        }
        var cacheHitTokens = response.promptTokensDetails() == null
                ? 0
                : value(response.promptTokensDetails().cachedTokens());
        return new LlmUsage(
                promptTokens,
                completionTokens,
                totalTokens,
                cacheHitTokens,
                Math.max(0, promptTokens - cacheHitTokens),
                response.completionTokensDetails() == null
                        ? 0
                        : value(response.completionTokensDetails().reasoningTokens())
        );
    }

    private MistralResponse parseResponse(String responseBody, String callId) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        try {
            return objectMapper.readValue(responseBody, MistralResponse.class);
        } catch (JsonProcessingException exception) {
            log.error("Mistral response JSON could not be parsed: callId={}", callId, exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Mistral returned malformed JSON");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "<serialization failed>";
        }
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MistralRequest(
            String model,
            List<LlmMessage> messages,
            Double temperature,
            @JsonProperty("top_p") Double topP,
            @JsonProperty("max_tokens") Integer maxTokens,
            Object stop,
            @JsonProperty("response_format") ResponseFormat responseFormat
    ) {
    }

    record ResponseFormat(String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MistralResponse(String model, List<Choice> choices, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("prompt_tokens") Long promptTokens,
            @JsonProperty("completion_tokens") Long completionTokens,
            @JsonProperty("total_tokens") Long totalTokens,
            @JsonProperty("prompt_tokens_details") PromptTokensDetails promptTokensDetails,
            @JsonProperty("completion_tokens_details") CompletionTokensDetails completionTokensDetails
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromptTokensDetails(@JsonProperty("cached_tokens") Long cachedTokens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompletionTokensDetails(@JsonProperty("reasoning_tokens") Long reasoningTokens) {
    }
}
