package com.example.aichat.task.day2.recipe.validator;

import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.RequestGuardConfig;
import com.example.aichat.common.profile.model.ResponseModeConfig;
import com.example.aichat.task.day2.recipe.enums.RecipeIntentVerdict;
import com.example.aichat.common.llm.LlmClient;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.validator.RequestGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.example.aichat.common.profile.service.AgentLlmRequestFactory.create;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Проверяет пользовательский запрос до запуска основной генерации LLM. */
@Component
public class RecipeIntentRequestGuard implements RequestGuard {

    private static final Logger log = LoggerFactory.getLogger(RecipeIntentRequestGuard.class);
    private static final Pattern VERDICT_PATTERN = Pattern.compile(
            "\\[\\[(ALLOW_RECIPE|REJECT_NOT_FOOD|REJECT_UNRELATED|REJECT_UNCLEAR)]]",
            Pattern.CASE_INSENSITIVE
    );
    private static final int MAX_CONTEXT_MESSAGE_LENGTH = 500;
    private static final int MAX_DISH_NAME_LENGTH = 200;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public RecipeIntentRequestGuard(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "recipe-intent";
    }

    @Override
    public void validate(
            AgentProfile profile,
            RequestGuardConfig config,
            List<LlmMessage> validatedMessages
    ) {
        var classifierMode = new ResponseModeConfig(
                "request-guard",
                "Request guard",
                "Internal semantic request classification",
                null,
                "text",
                config.maxTokens(),
                null,
                null
        );
        RecipeIntentVerdict verdict = null;
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                var classifierMessages = classifierMessages(
                        config,
                        validatedMessages,
                        attempt > 1
                );
                var result = llmClient.complete(create(
                        profile,
                        classifierMode,
                        classifierMessages
                ));
                verdict = extractVerdict(result.content());
                log.info(
                        "Recipe classifier verdict accepted: attempt={}, verdict={}",
                        attempt,
                        verdict
                );
                break;
            } catch (InvalidClassifierResponseException exception) {
                log.warn(
                        "Recipe classifier response rejected: attempt={}, reason={}",
                        attempt,
                        exception.getMessage()
                );
                if (attempt == config.maxAttempts()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "Не удалось проверить, является ли запрос рецептом. Повторите запрос."
                    );
                }
            }
        }

        if (verdict == null) {
            throw new IllegalStateException("Recipe classifier finished without a verdict");
        }
        if (verdict != RecipeIntentVerdict.ALLOW_RECIPE) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    rejectionMessage(verdict)
            );
        }
    }

    private List<LlmMessage> classifierMessages(
            RequestGuardConfig config,
            List<LlmMessage> validatedMessages,
            boolean retry
    ) {
        List<LlmMessage> messages = new ArrayList<>();
        var instruction = config.instruction().trim();
        if (retry) {
            instruction += """


                    Предыдущий ответ не прошёл проверку формата.
                    Верни ровно один допустимый маркер в двойных квадратных скобках.
                    Не перечисляй варианты и не добавляй пояснения.
                    """;
        }
        messages.add(new LlmMessage("system", instruction));
        messages.add(new LlmMessage(
                "user",
                serializeClassifierInput(classifierInput(validatedMessages))
        ));
        return List.copyOf(messages);
    }

    private ClassifierInput classifierInput(List<LlmMessage> validatedMessages) {
        var currentMessage = validatedMessages.getLast();
        if (!"user".equals(currentMessage.role())) {
            throw new IllegalArgumentException("Last validated message must have the user role");
        }

        var history = validatedMessages.subList(1, validatedMessages.size() - 1);
        var activeDish = latestDishName(history).orElse(null);
        var previousUserMessage = activeDish == null
                ? latestUserMessage(history).map(RecipeIntentRequestGuard::compactContext).orElse(null)
                : null;
        return new ClassifierInput(
                !history.isEmpty(),
                activeDish,
                previousUserMessage,
                currentMessage.content()
        );
    }

    private Optional<String> latestDishName(List<LlmMessage> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            var message = history.get(index);
            if (!"assistant".equals(message.role())) {
                continue;
            }
            try {
                var root = objectMapper.readTree(message.content());
                var dishName = root.path("dishName").asText("").trim();
                if (!dishName.isEmpty()
                        && root.path("requiredIngredients").isArray()
                        && root.path("cookingTime").isTextual()) {
                    return Optional.of(compact(dishName, MAX_DISH_NAME_LENGTH));
                }
            } catch (JsonProcessingException ignored) {
                // Text-mode assistant replies are intentionally not forwarded to the classifier.
            }
        }
        return Optional.empty();
    }

    private static Optional<String> latestUserMessage(List<LlmMessage> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            var message = history.get(index);
            if ("user".equals(message.role())) {
                return Optional.of(message.content());
            }
        }
        return Optional.empty();
    }

    private String serializeClassifierInput(ClassifierInput input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize recipe classifier input", exception);
        }
    }

    private static String compactContext(String value) {
        return compact(value, MAX_CONTEXT_MESSAGE_LENGTH);
    }

    private static String compact(String value, int maxLength) {
        var normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 3).stripTrailing() + "...";
    }

    private static RecipeIntentVerdict extractVerdict(String content) {
        var matcher = VERDICT_PATTERN.matcher(content);
        var verdicts = EnumSet.noneOf(RecipeIntentVerdict.class);
        while (matcher.find()) {
            verdicts.add(RecipeIntentVerdict.valueOf(matcher.group(1).toUpperCase(Locale.ROOT)));
        }
        if (verdicts.isEmpty()) {
            throw invalidClassifierResponse("verdict marker is missing");
        }
        if (verdicts.size() > 1) {
            throw invalidClassifierResponse("multiple verdict markers found");
        }
        return verdicts.iterator().next();
    }

    private static String rejectionMessage(RecipeIntentVerdict verdict) {
        return switch (verdict) {
            case REJECT_NOT_FOOD -> "Укажите существующее съедобное блюдо, для которого нужен рецепт.";
            case REJECT_UNRELATED -> "Профиль рецептов принимает только запросы о приготовлении блюд.";
            case REJECT_UNCLEAR -> "Укажите название существующего блюда, которое вы хотите приготовить.";
            case ALLOW_RECIPE -> throw new IllegalArgumentException(
                    "Allowed recipe verdict cannot be rejected"
            );
        };
    }

    private static InvalidClassifierResponseException invalidClassifierResponse(String reason) {
        return new InvalidClassifierResponseException(reason);
    }

    private record ClassifierInput(
            boolean activeRecipeContext,
            String activeDish,
            String previousUserMessage,
            String currentMessage
    ) {
    }

    private static final class InvalidClassifierResponseException extends RuntimeException {
        private InvalidClassifierResponseException(String message) {
            super(message);
        }
    }
}
