package com.example.aichat.common.llm;

import com.example.aichat.common.llm.enums.LlmProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Находит реализацию LLM-клиента по выбранному провайдеру. */
@Component
public class LlmProviderRegistry {

    private final Map<LlmProvider, LlmProviderClient> clients;

    public LlmProviderRegistry(List<LlmProviderClient> clients) {
        Map<LlmProvider, LlmProviderClient> indexed = new EnumMap<>(LlmProvider.class);
        for (var client : clients) {
            var previous = indexed.put(client.provider(), client);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate LLM provider client: " + client.provider().id()
                );
            }
        }
        this.clients = Map.copyOf(indexed);
    }

    public LlmProviderClient get(LlmProvider provider) {
        var client = clients.get(provider);
        if (client == null) {
            throw new IllegalArgumentException("LLM provider is not configured: " + provider.id());
        }
        return client;
    }
}
