package com.example.aichat.task.chat.service;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.ResponseModeConfig;
import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.task.chat.model.ChatCommand;
import com.example.aichat.task.chat.model.ChatResult;
import com.example.aichat.task.chat.model.ChatResultMeta;
import com.example.aichat.config.ChatProperties;
import com.example.aichat.config.enums.Mode;
import com.example.aichat.common.llm.LlmClient;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.inputpolicy.InputPolicyRegistry;

import com.example.aichat.common.validator.RequestGuardRegistry;
import com.example.aichat.common.outputpolicy.OutputPolicy;
import com.example.aichat.common.outputpolicy.OutputPolicyRegistry;
import com.example.aichat.common.outputpolicy.model.OutputPolicyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static com.example.aichat.common.profile.service.AgentLlmRequestFactory.create;

/** Координирует полный pipeline чата: input, validation, LLM и output-policy. */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MIN_STRUCTURED_RETRY_TOKENS = 1_000;
    private static final int MAX_STRUCTURED_RETRY_TOKENS = 4_000;

    private final ChatProperties properties;
    private final AgentRegistry agentRegistry;
    private final InputPolicyRegistry inputPolicies;
    private final RequestGuardRegistry requestGuards;
    private final LlmClient llmClient;
    private final OutputPolicyRegistry outputPolicies;

    public ChatService(
            ChatProperties properties,
            AgentRegistry agentRegistry,
            InputPolicyRegistry inputPolicies,
            RequestGuardRegistry requestGuards,
            LlmClient llmClient,
            OutputPolicyRegistry outputPolicies
    ) {
        this.properties = properties;
        this.agentRegistry = agentRegistry;
        this.inputPolicies = inputPolicies;
        this.requestGuards = requestGuards;
        this.llmClient = llmClient;
        this.outputPolicies = outputPolicies;
    }

    public ChatResult reply(ChatCommand request) {
        var profileId = StringUtils.hasText(request.profileId())
                ? request.profileId()
                : properties.defaultAgentId();
        var profile = agentRegistry.get(profileId);
        var responseModeId = StringUtils.hasText(request.responseMode())
                ? request.responseMode()
                : profile.defaultResponseMode();
        var responseMode = resolveResponseMode(profileId, responseModeId, profile.responseMode(responseModeId));
        var inputPolicy = inputPolicies.get(profile.inputPolicy().type());
        var messages = inputPolicy.buildMessages(
                profile,
                responseMode,
                request.message(),
                request.history()
        );

        CompletionOutcome completion;
        String source;
        if (properties.mode() == Mode.FALLBACK) {
            var outputPolicy = outputPolicies.get(responseMode.outputPolicy());
            var llmResult = new LlmResult("", null, null);
            completion = new CompletionOutcome(
                    llmResult,
                    outputPolicy.fallback(),
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
        return new ChatResult(
                output.reply(),
                output.structuredReply(),
                source,
                profile.id(),
                responseMode.id(),
                new ChatResultMeta(
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
            ChatCommand request,
            ResponseModeConfig responseMode,
            List<LlmMessage> messages
    ) {
        var outputPolicy = outputPolicies.get(responseMode.outputPolicy());
        var firstResult = llmClient.complete(create(profile, responseMode, messages));
        var retryReason = structuredRetryReason(outputPolicy, firstResult);

        if (retryReason == null) {
            return new CompletionOutcome(
                    firstResult,
                    outputPolicy.apply(firstResult),
                    responseMode
            );
        }

        var retryMode = structuredRetryMode(responseMode, outputPolicy);
        log.warn(
                "Retrying structured LLM output: profile={}, mode={}, reason={}, "
                        + "finishReason={}, retryMaxTokens={}",
                profile.id(),
                responseMode.id(),
                retryReason,
                firstResult.finishReason(),
                retryMode.maxTokens()
        );

        var retryMessages = inputPolicies.get(profile.inputPolicy().type()).buildMessages(
                profile,
                retryMode,
                request.message(),
                request.history()
        );
        var retryResult = llmClient.complete(create(profile, retryMode, retryMessages));
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
            OutputPolicy outputPolicy,
            LlmResult result
    ) {
        if (!outputPolicy.supportsStructuredRetry()) {
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

    private static ResponseModeConfig structuredRetryMode(
            ResponseModeConfig responseMode,
            OutputPolicy outputPolicy
    ) {
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
        var retryInstruction = originalInstruction + """
                Это повторная попытка: предыдущий ответ был обрезан или не прошёл проверку схемы.
                Верни полный, компактный JSON строго заданной структуры. Не добавляй Markdown,
                пояснения и необязательные подробности. Обязательно закрой все строки, массивы и объект.
                """ + outputPolicy.retryConstraint();
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

    private record CompletionOutcome(
            LlmResult llmResult,
            OutputPolicyResult output,
            ResponseModeConfig effectiveMode
    ) {
    }
}
