package com.example.aichat.task.day2.responseformat.outputpolicy.dto;

import java.util.List;

/** Представляет проверенный рецепт с названием, ингредиентами и временем готовки. */
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
