package com.example.aichat.task.day2.responseformat.outputpolicy;

import com.example.aichat.common.llm.model.LlmResult;
import com.example.aichat.common.outputpolicy.OutputPolicy;
import com.example.aichat.common.outputpolicy.model.OutputPolicyResult;
import com.example.aichat.task.day2.responseformat.outputpolicy.dto.RecipePayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Проверяет и преобразует строгий JSON-рецепт из задания Дня 2. */
@Component
public class RecipeJsonOutputPolicy implements OutputPolicy {

    private final ObjectMapper objectMapper;

    public RecipeJsonOutputPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public String type() {
        return "recipe-json";
    }

    @Override
    public OutputPolicyResult apply(LlmResult result) {
        try {
            var payload = objectMapper.readValue(result.content(), RecipePayload.class);
            return new OutputPolicyResult(payload.dishName(), payload);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "LLM returned JSON that does not match the recipe schema",
                    exception
            );
        }
    }

    @Override
    public OutputPolicyResult fallback() {
        var payload = new RecipePayload(
                "Тестовый рецепт",
                java.util.List.of("Основной ингредиент — по вкусу"),
                "30 минут"
        );
        return new OutputPolicyResult(payload.dishName(), payload);
    }

    @Override
    public boolean supportsStructuredRetry() {
        return true;
    }

    @Override
    public String retryConstraint() {
        return "Верни не более 15 ингредиентов, каждый короче 100 символов, "
                + "а весь JSON — не длиннее 1800 символов.";
    }

}
