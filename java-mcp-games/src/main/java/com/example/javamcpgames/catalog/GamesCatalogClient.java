package com.example.javamcpgames.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Единственная точка HTTP-доступа к отдельному mock API игр. */
@Service
public class GamesCatalogClient {

    private final RestClient restClient;

    public GamesCatalogClient(RestClient.Builder builder, @Value("${games.api.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /** Ищет по названию или описанию; неизвестная игра возвращает пустой список. */
    public GamesResult search(String query) {
        if (query == null || query.isBlank() || query.strip().length() > 100) {
            throw new IllegalArgumentException("Запрос поиска должен содержать от 1 до 100 символов");
        }
        String term = query.strip();
        try {
            GamesResult result = restClient.get()
                    .uri(uri -> uri.path("/games").queryParam("query", term).build())
                    .retrieve()
                    .body(GamesResult.class);
            if (result == null || result.games() == null) {
                throw new IllegalStateException("Каталог игр вернул некорректный ответ");
            }
            return result;
        } catch (RestClientException ex) {
            throw new IllegalStateException("Каталог игр временно недоступен", ex);
        }
    }

    /** Получает только последние десять игр новее подтверждённого курсора. */
    public RecentGamesResult newGames(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > 10) {
            throw new IllegalArgumentException("Курсор должен быть неотрицательным, лимит — от 1 до 10");
        }
        try {
            RecentGamesResult result = restClient.get()
                    .uri(uri -> uri.path("/games/latest")
                            .queryParam("after_id", afterId)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .body(RecentGamesResult.class);
            if (result == null || result.games() == null) {
                throw new IllegalStateException("Каталог игр вернул некорректный ответ");
            }
            return result;
        } catch (RestClientException ex) {
            throw new IllegalStateException("Каталог игр временно недоступен", ex);
        }
    }
}
