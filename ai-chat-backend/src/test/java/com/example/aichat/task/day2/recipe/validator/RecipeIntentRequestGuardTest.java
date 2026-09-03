package com.example.aichat.task.day2.recipe.validator;

import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.config.ChatProperties;
import com.example.aichat.config.enums.Mode;
import com.example.aichat.common.llm.LlmClient;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.llm.model.LlmResult;
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
        LlmClient llmClient = request -> {
            capturedMessages.set(request.messages());
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
        LlmClient llmClient = request -> {
            if (attempts.incrementAndGet() == 1) {
                return new LlmResult(
                        "Ответ без маркера",
                        "test-model",
                        "stop"
                );
            }
            assertThat(request.messages().getFirst().content())
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
        LlmClient llmClient = request -> {
            assertThat(request.maxTokens()).isEqualTo(20);
            assertThat(request.responseFormat()).isNull();
            assertThat(request.messages().getFirst().role()).isEqualTo("system");
            assertThat(request.messages().getFirst().content()).contains("внутренний классификатор");
            assertThat(request.messages()).hasSize(2);
            assertThat(request.messages().get(1).role()).isEqualTo("user");
            assertThat(request.messages().get(1).content()).contains("currentMessage");
            return new LlmResult(content.trim(), "test-model", "stop");
        };
        return new RecipeIntentRequestGuard(llmClient, OBJECT_MAPPER);
    }

    private static com.example.aichat.common.profile.model.AgentProfile recipeProfile() {
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
