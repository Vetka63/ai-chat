package com.example.aichat.common.llm;

import com.example.aichat.common.llm.enums.LlmProvider;
import com.example.aichat.common.llm.model.LlmCompletionRequest;
import com.example.aichat.common.llm.model.LlmResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmProviderRegistryTest {

    @Test
    void returnsClientRegisteredForProvider() {
        var deepSeek = client(LlmProvider.DEEPSEEK);
        var mistral = client(LlmProvider.MISTRAL);
        var registry = new LlmProviderRegistry(List.of(deepSeek, mistral));

        assertThat(registry.get(LlmProvider.DEEPSEEK)).isSameAs(deepSeek);
        assertThat(registry.get(LlmProvider.MISTRAL)).isSameAs(mistral);
    }

    @Test
    void rejectsDuplicateProviderClients() {
        assertThatThrownBy(() -> new LlmProviderRegistry(List.of(
                client(LlmProvider.DEEPSEEK),
                client(LlmProvider.DEEPSEEK)
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate LLM provider client");
    }

    private static LlmProviderClient client(LlmProvider provider) {
        return new LlmProviderClient() {
            @Override
            public LlmProvider provider() {
                return provider;
            }

            @Override
            public LlmResult complete(LlmCompletionRequest request) {
                return new LlmResult("answer", "model", "stop");
            }
        };
    }
}
