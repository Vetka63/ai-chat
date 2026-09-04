package com.example.aichat.task.day5.modelcomparison.config;

import com.example.aichat.common.profile.registry.AgentRegistry;
import com.example.aichat.config.ChatProperties;
import com.example.aichat.config.enums.Mode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelComparisonConfigTest {

    @Test
    void loadsThreeProviderModelsAndPinnedPricing() {
        var properties = new ChatProperties(
                Mode.FALLBACK,
                "https://api.deepseek.com",
                "",
                "deepseek-v4-flash",
                "classpath*:agents/*.yml",
                "general",
                false,
                ChatProperties.Pricing.free(),
                List.of("http://localhost:5173")
        );
        var registry = new AgentRegistry(new PathMatchingResourcePatternResolver(), properties);
        var provider = new ModelComparisonConfigProvider(
                new ObjectMapper().findAndRegisterModules()
        );

        var config = provider.get(registry.get("day5-model-comparison"));

        assertThat(config.models()).extracting(ModelVariantConfig::id)
                .containsExactly("weak", "medium", "strong");
        assertThat(config.models()).extracting(model -> model.provider().id())
                .containsExactly("mistral", "deepseek", "deepseek");
        assertThat(config.models().getFirst().model()).isEqualTo("ministral-3b-2512");
        assertThat(config.temperature()).isZero();
        assertThat(config.topP()).isEqualTo(1.0);
        assertThat(config.maxTokens()).isEqualTo(1200);
        assertThat(config.judge().llm().model()).isEqualTo("deepseek-v4-pro");
        assertThat(config.judge().maxAttempts()).isEqualTo(2);
    }
}
