package com.example.aichat.task.day5.modelcomparison.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Описывает HTTP-запрос на сравнение одной задачи между тремя моделями. */
public record ModelComparisonRequest(
        @NotBlank(message = "Task must not be blank")
        @Size(max = 10000, message = "Task must not exceed 10000 characters")
        String task,

        @NotBlank(message = "Profile id must not be blank")
        @Pattern(
                regexp = "^[a-z][a-z0-9-]{1,63}$",
                message = "Profile id has an invalid format"
        )
        String profileId
) {
}
