package com.example.aichat.service.output;

import com.example.aichat.llm.LlmResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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

    public record RecipePayload(
            String dishName,
            List<String> requiredIngredients,
            String cookingTime
    ) {
        public RecipePayload {
            dishName = requiredText(dishName);
            cookingTime = requiredText(cookingTime);
            if (requiredIngredients == null || requiredIngredients.isEmpty()) {
                throw new IllegalArgumentException("Ingredients must not be empty");
            }
            requiredIngredients = requiredIngredients.stream()
                    .map(RecipePayload::requiredText)
                    .toList();
        }

        private static String requiredText(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Text field must not be blank");
            }
            return value.trim();
        }
    }

}
