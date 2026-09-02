package com.example.aichat.service;

import com.example.aichat.agent.AgentProfile;
import com.example.aichat.agent.AgentProfile.ResponseModeConfig;
import com.example.aichat.agent.AgentRegistry;
import com.example.aichat.api.ChatController.ChatMeta;
import com.example.aichat.api.ChatController.ChatRequest;
import com.example.aichat.api.ChatController.ChatResponse;
import com.example.aichat.config.ChatProperties;
import com.example.aichat.enums.Mode;
import com.example.aichat.llm.LlmClient;
import com.example.aichat.llm.LlmMessage;
import com.example.aichat.llm.LlmResult;
import com.example.aichat.service.input.InputPolicy;
import com.example.aichat.service.input.RequestGuardRegistry;
import com.example.aichat.service.output.OutputPolicy;
import com.example.aichat.service.output.OutputPolicyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MIN_STRUCTURED_RETRY_TOKENS = 1_000;
    private static final int MAX_STRUCTURED_RETRY_TOKENS = 4_000;

    private static final List<String> FALLBACK_REPLIES = List.of(
            "Интересная мысль. Расскажите об этом немного подробнее.",
            "Я получил ваше сообщение — fallback-режим работает.",
            "Хороший вопрос! Подключите DeepSeek, чтобы получить содержательный ответ.",
            "Сейчас отвечает тестовый помощник. Связь с backend установлена.",
            "Принято. В боевом режиме этот запрос будет передан языковой модели."
    );

    private final ChatProperties properties;
    private final AgentRegistry agentRegistry;
    private final InputPolicy inputPolicy;
    private final RequestGuardRegistry requestGuards;
    private final LlmClient llmClient;
    private final OutputPolicyRegistry outputPolicies;

    public ChatService(
            ChatProperties properties,
            AgentRegistry agentRegistry,
            InputPolicy inputPolicy,
            RequestGuardRegistry requestGuards,
            LlmClient llmClient,
            OutputPolicyRegistry outputPolicies
    ) {
        this.properties = properties;
        this.agentRegistry = agentRegistry;
        this.inputPolicy = inputPolicy;
        this.requestGuards = requestGuards;
        this.llmClient = llmClient;
        this.outputPolicies = outputPolicies;
    }

    public ChatResponse reply(ChatRequest request) {
        var profileId = StringUtils.hasText(request.profileId())
                ? request.profileId()
                : properties.defaultAgentId();
        var profile = agentRegistry.get(profileId);
        var responseModeId = StringUtils.hasText(request.responseMode())
                ? request.responseMode()
                : profile.defaultResponseMode();
        var responseMode = resolveResponseMode(profileId, responseModeId, profile.responseMode(responseModeId));
        var messages = inputPolicy.buildMessages(profile, responseMode, request);

        CompletionOutcome completion;
        String source;
        if (properties.mode() == Mode.FALLBACK) {
            var llmResult = new LlmResult(fallbackContent(responseMode), null, null);
            completion = new CompletionOutcome(
                    llmResult,
                    outputPolicies.get(responseMode.outputPolicy()).apply(llmResult),
                    responseMode
            );
            source = "fallback";
        } else {
            requestGuards.validate(profile, messages);
            completion = completeWithStructuredRetry(
                    profile,
                    request,
                    responseMode,
                    messages
            );
            source = "llm";
        }

        var llmResult = completion.llmResult();
        var output = completion.output();
        var effectiveMode = completion.effectiveMode();
        return new ChatResponse(
                output.reply(),
                output.structuredReply(),
                source,
                profile.id(),
                responseMode.id(),
                new ChatMeta(
                        llmResult.model(),
                        llmResult.finishReason(),
                        effectiveMode.maxTokens(),
                        effectiveMode.responseFormat(),
                        effectiveMode.stop()
                )
        );
    }

    private CompletionOutcome completeWithStructuredRetry(
            AgentProfile profile,
            ChatRequest request,
            ResponseModeConfig responseMode,
            List<LlmMessage> messages
    ) {
        var outputPolicy = outputPolicies.get(responseMode.outputPolicy());
        var firstResult = llmClient.complete(profile, responseMode, messages);
        var retryReason = structuredRetryReason(responseMode, outputPolicy, firstResult);

        if (retryReason == null) {
            return new CompletionOutcome(
                    firstResult,
                    outputPolicy.apply(firstResult),
                    responseMode
            );
        }

        var retryMode = structuredRetryMode(responseMode);
        log.warn(
                "Retrying structured LLM output: profile={}, mode={}, reason={}, "
                        + "finishReason={}, retryMaxTokens={}",
                profile.id(),
                responseMode.id(),
                retryReason,
                firstResult.finishReason(),
                retryMode.maxTokens()
        );

        var retryMessages = inputPolicy.buildMessages(profile, retryMode, request);
        var retryResult = llmClient.complete(profile, retryMode, retryMessages);
        try {
            var retryOutput = outputPolicy.apply(retryResult);
            log.info(
                    "Structured LLM output recovered after retry: profile={}, mode={}, finishReason={}",
                    profile.id(),
                    responseMode.id(),
                    retryResult.finishReason()
            );
            return new CompletionOutcome(retryResult, retryOutput, retryMode);
        } catch (ResponseStatusException exception) {
            log.error(
                    "Structured LLM output is invalid after retry: profile={}, mode={}, finishReason={}",
                    profile.id(),
                    responseMode.id(),
                    retryResult.finishReason(),
                    exception
            );
            throw exception;
        }
    }

    private static String structuredRetryReason(
            ResponseModeConfig responseMode,
            OutputPolicy outputPolicy,
            LlmResult result
    ) {
        if (responseMode.responseFormat() == null) {
            return null;
        }
        if ("length".equalsIgnoreCase(result.finishReason())) {
            return "token limit reached";
        }
        try {
            outputPolicy.apply(result);
            return null;
        } catch (ResponseStatusException exception) {
            return "JSON/schema validation failed";
        }
    }

    private static ResponseModeConfig structuredRetryMode(ResponseModeConfig responseMode) {
        var configuredMaxTokens = responseMode.maxTokens() == null
                ? MIN_STRUCTURED_RETRY_TOKENS
                : responseMode.maxTokens();
        var retryMaxTokens = Math.min(
                MAX_STRUCTURED_RETRY_TOKENS,
                Math.max(MIN_STRUCTURED_RETRY_TOKENS, configuredMaxTokens * 2)
        );
        var originalInstruction = responseMode.instruction() == null
                ? ""
                : responseMode.instruction().trim() + "\n\n";
        var outputLengthInstruction = switch (responseMode.outputPolicy()) {
            case "answer-json" -> """
                    Значение поля answer должно быть не длиннее 500 символов,
                    а весь JSON — не длиннее 650 символов.
                    """;
            case "recipe-json" -> """
                    Верни не более 15 ингредиентов, каждый короче 100 символов,
                    а весь JSON — не длиннее 1800 символов.
                    """;
            default -> "Весь JSON должен быть не длиннее 1800 символов.\n";
        };
        var retryInstruction = originalInstruction + """
                Это повторная попытка: предыдущий ответ был обрезан или не прошёл проверку схемы.
                Верни полный, компактный JSON строго заданной структуры. Не добавляй Markdown,
                пояснения и необязательные подробности. Обязательно закрой все строки, массивы и объект.
                """ + outputLengthInstruction;
        return new ResponseModeConfig(
                responseMode.id() + "-retry",
                responseMode.name(),
                responseMode.description(),
                retryInstruction,
                responseMode.outputPolicy(),
                retryMaxTokens,
                responseMode.stop(),
                responseMode.responseFormat()
        );
    }

    private static ResponseModeConfig resolveResponseMode(
            String profileId,
            String responseModeId,
            ResponseModeConfig responseMode
    ) {
        if (responseMode == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unknown response mode for profile " + profileId + ": " + responseModeId
            );
        }
        return responseMode;
    }

    private static String fallbackContent(ResponseModeConfig responseMode) {
        return switch (responseMode.outputPolicy()) {
            case "answer-json" -> """
                    {"answer":"Fallback-режим работает. Подключите DeepSeek для содержательного ответа."}
                    """;
            case "recipe-json" -> """
                    {
                      "dishName":"Тестовый рецепт",
                      "requiredIngredients":["Основной ингредиент — по вкусу"],
                      "cookingTime":"30 минут"
                    }
                    """;
            default -> FALLBACK_REPLIES.get(
                    ThreadLocalRandom.current().nextInt(FALLBACK_REPLIES.size())
            );
        };
    }

    private record CompletionOutcome(
            LlmResult llmResult,
            OutputPolicy.OutputPolicyResult output,
            ResponseModeConfig effectiveMode
    ) {
    }
}
