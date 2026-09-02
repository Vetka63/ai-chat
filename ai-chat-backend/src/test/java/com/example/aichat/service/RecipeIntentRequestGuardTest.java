package com.example.aichat.service;

import com.example.aichat.agent.AgentRegistry;
import com.example.aichat.config.ChatProperties;
import com.example.aichat.enums.Mode;
import com.example.aichat.llm.LlmClient;
import com.example.aichat.llm.LlmMessage;
import com.example.aichat.llm.LlmResult;
import com.example.aichat.service.input.RecipeIntentRequestGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeIntentRequestGuardTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void sendsOnlyCurrentMessageAndMinimalRecipeContextToClassifier() throws Exception {
        var profile = recipeProfile();
        var capturedMessages = new AtomicReference<List<LlmMessage>>();
        LlmClient llmClient = (ignoredProfile, responseMode, messages) -> {
            capturedMessages.set(messages);
            return new LlmResult("[[ALLOW_RECIPE]]", "test-model", "stop");
        };
        var guard = new RecipeIntentRequestGuard(llmClient, OBJECT_MAPPER);
        var previousRecipe = """
                {"dishName":"Борщ","requiredIngredients":["Свекла — 2 шт."],"cookingTime":"90 минут"}
                """.trim();

        guard.validate(
                profile,
                profile.requestGuard(),
                List.of(
                        new LlmMessage("system", "recipe prompt"),
                        new LlmMessage("user", "Дай рецепт борща"),
                        new LlmMessage("assistant", previousRecipe),
                        new LlmMessage("user", "А можно без лука?")
                )
        );

        assertThat(capturedMessages.get()).hasSize(2);
        assertThat(capturedMessages.get().get(1).role()).isEqualTo("user");
        var input = OBJECT_MAPPER.readTree(capturedMessages.get().get(1).content());
        assertThat(input.path("activeRecipeContext").asBoolean()).isTrue();
        assertThat(input.path("activeDish").asText()).isEqualTo("Борщ");
        assertThat(input.path("previousUserMessage").isNull()).isTrue();
        assertThat(input.path("currentMessage").asText()).isEqualTo("А можно без лука?");
        assertThat(capturedMessages.get().get(1).content())
                .doesNotContain("requiredIngredients", "Свекла");
    }

    @Test
    void allowsARequestForAnEdibleDish() {
        var profile = recipeProfile();
        var guard = guardReturning("[[ALLOW_RECIPE]]");

        assertThatCode(() -> guard.validate(
                profile,
                profile.requestGuard(),
                List.of(
                        new LlmMessage("system", "recipe prompt"),
                        new LlmMessage("user", "Дай рецепт борща")
                )
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsANonFoodObjectWithAUserFriendlyMessage() {
        var profile = recipeProfile();
        var guard = guardReturning("[[REJECT_NOT_FOOD]]");

        assertThatThrownBy(() -> guard.validate(
                profile,
                profile.requestGuard(),
                List.of(
                        new LlmMessage("system", "recipe prompt"),
                        new LlmMessage("user", "Дай рецепт автомобиля")
                )
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    var responseException = (ResponseStatusException) exception;
                    assertThat(responseException.getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(responseException.getReason())
                            .contains("существующее съедобное блюдо");
                });
    }

    @Test
    void rejectsAnUnrelatedRequestWithAUserFriendlyMessage() {
        var profile = recipeProfile();
        var guard = guardReturning("[[REJECT_UNRELATED]]");

        assertThatThrownBy(() -> guard.validate(
                profile,
                profile.requestGuard(),
                List.of(
                        new LlmMessage("system", "recipe prompt"),
                        new LlmMessage("user", "Какая сегодня погода?")
                )
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("только запросы о приготовлении блюд");
    }

    @Test
    void rejectsMalformedClassifierOutput() {
        var profile = recipeProfile();
        var guard = guardReturning("Не могу определить результат.");

        assertThatThrownBy(() -> guard.validate(
                profile,
                profile.requestGuard(),
                List.of(
                        new LlmMessage("system", "recipe prompt"),
                        new LlmMessage("user", "Дай рецепт автомобиля")
                )
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    var responseException = (ResponseStatusException) exception;
                    assertThat(responseException.getStatusCode())
                            .isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(responseException.getReason())
                            .isEqualTo("Не удалось проверить, является ли запрос рецептом. Повторите запрос.");
                });
    }

    @Test
    void retriesOnceAfterAnInvalidClassifierResponse() {
        var profile = recipeProfile();
        var attempts = new AtomicInteger();
        LlmClient llmClient = (ignoredProfile, responseMode, messages) -> {
            if (attempts.incrementAndGet() == 1) {
                return new LlmResult(
                        "Ответ без маркера",
                        "test-model",
                        "stop"
                );
            }
            assertThat(messages.getFirst().content())
                    .contains("Предыдущий ответ не прошёл проверку формата");
            return new LlmResult(
                    "[[ALLOW_RECIPE]]",
                    "test-model",
                    "stop"
            );
        };
        var guard = new RecipeIntentRequestGuard(llmClient, OBJECT_MAPPER);

        assertThatCode(() -> guard.validate(
                profile,
                profile.requestGuard(),
                List.of(
                        new LlmMessage("system", "recipe prompt"),
                        new LlmMessage("user", "Дай рецепт борща")
                )
        )).doesNotThrowAnyException();
        assertThat(attempts).hasValue(2);
    }

    @Test
    void acceptsOneKnownMarkerInsideAdditionalText() {
        var profile = recipeProfile();
        var guard = guardReturning("Решение классификатора: [[ALLOW_RECIPE]]");

        assertThatCode(() -> guard.validate(
                profile,
                profile.requestGuard(),
                List.of(
                        new LlmMessage("system", "recipe prompt"),
                        new LlmMessage("user", "Дай рецепт борща")
                )
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsMultipleDifferentMarkers() {
        var profile = recipeProfile();
        var guard = guardReturning("[[ALLOW_RECIPE]] или [[REJECT_UNCLEAR]]");

        assertThatThrownBy(() -> guard.validate(
                profile,
                profile.requestGuard(),
                List.of(
                        new LlmMessage("system", "recipe prompt"),
                        new LlmMessage("user", "Хочу что-нибудь")
                )
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode()
                ).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    private RecipeIntentRequestGuard guardReturning(String content) {
        LlmClient llmClient = (profile, responseMode, messages) -> {
            assertThat(responseMode.maxTokens()).isEqualTo(20);
            assertThat(responseMode.responseFormat()).isNull();
            assertThat(messages.getFirst().role()).isEqualTo("system");
            assertThat(messages.getFirst().content()).contains("внутренний классификатор");
            assertThat(messages).hasSize(2);
            assertThat(messages.get(1).role()).isEqualTo("user");
            assertThat(messages.get(1).content()).contains("currentMessage");
            return new LlmResult(content.trim(), "test-model", "stop");
        };
        return new RecipeIntentRequestGuard(llmClient, OBJECT_MAPPER);
    }

    private static com.example.aichat.agent.AgentProfile recipeProfile() {
        var properties = new ChatProperties(
                Mode.FALLBACK,
                "https://api.deepseek.com",
                "",
                "test-model",
                "classpath*:agents/*.yml",
                "general",
                false,
                ChatProperties.Pricing.free(),
                List.of("http://localhost:5173")
        );
        return new AgentRegistry(
                new PathMatchingResourcePatternResolver(),
                properties
        ).get("recipe");
    }
}
