package com.example.aichat.service;

import com.example.aichat.llm.LlmResult;
import com.example.aichat.service.output.RecipeJsonOutputPolicy;
import com.example.aichat.service.output.RecipeJsonOutputPolicy.RecipePayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeJsonOutputPolicyTest {

    private final RecipeJsonOutputPolicy policy = new RecipeJsonOutputPolicy(
            new ObjectMapper().findAndRegisterModules()
    );

    @Test
    void acceptsAndNormalizesACompleteRecipe() {
        var result = policy.apply(new LlmResult("""
                {
                  "dishName": " Овощной салат ",
                  "requiredIngredients": [" Помидоры — 2 шт. ", " Огурцы — 2 шт. "],
                  "cookingTime": " 15 минут "
                }
                """, "deepseek-v4-flash", "stop"));

        assertThat(result.reply()).isEqualTo("Овощной салат");
        assertThat(result.structuredReply()).isInstanceOf(RecipePayload.class);
        var payload = (RecipePayload) result.structuredReply();
        assertThat(payload.dishName()).isEqualTo("Овощной салат");
        assertThat(payload.requiredIngredients()).containsExactly(
                "Помидоры — 2 шт.",
                "Огурцы — 2 шт."
        );
        assertThat(payload.cookingTime()).isEqualTo("15 минут");
    }

    @Test
    void rejectsJsonThatDoesNotMatchTheSchema() {
        assertThatThrownBy(() -> policy.apply(new LlmResult("""
                {
                  "dishName": "Борщ",
                  "requiredIngredients": [],
                  "cookingTime": "1 час",
                  "unexpected": true
                }
                """, "deepseek-v4-flash", "stop")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("recipe schema");
    }
}
