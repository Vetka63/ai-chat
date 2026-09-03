package com.example.aichat.common.profile.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestGuardConfigTest {

    @Test
    void keepsOlderYamlCompatibleByDefaultingMaxAttempts() {
        var config = new RequestGuardConfig(
                "recipe-intent",
                "Classify the request",
                20,
                null
        );

        config.validate("recipe");

        assertThat(config.maxAttempts()).isEqualTo(2);
    }
}
