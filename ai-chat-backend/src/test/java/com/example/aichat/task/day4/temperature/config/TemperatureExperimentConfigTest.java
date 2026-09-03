package com.example.aichat.task.day4.temperature.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemperatureExperimentConfigTest {

    @Test
    void acceptsConfiguredChallengeTemperatures() {
        var config = new TemperatureExperimentConfig(
                List.of(
                        variant("precise", 0.0),
                        variant("balanced", 0.7),
                        variant("creative", 1.2),
                        variant("experimental", 2.0)
                ),
                1200,
                judge()
        );

        assertThatCode(() -> config.validate("day4-temperature")).doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateTemperatureValues() {
        var config = new TemperatureExperimentConfig(
                List.of(variant("first", 0.7), variant("second", 0.7)),
                1200,
                judge()
        );

        assertThatThrownBy(() -> config.validate("day4-temperature"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate temperature value");
    }

    private static TemperatureVariantConfig variant(String id, double value) {
        return new TemperatureVariantConfig(id, "Название", "Описание", value);
    }

    private static TemperatureJudgeConfig judge() {
        return new TemperatureJudgeConfig(
                new TemperatureJudgeLlmConfig("deepseek-v4-pro", "disabled", null, 0.0, 1.0),
                "Инструкция судьи",
                1800,
                2,
                3600
        );
    }
}
