package com.example.mockgames;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** HTTP API для прежнего поиска и нового потока десяти последних игр. */
@RestController
public class GamesController {
    private final GameRepository repository;

    public GamesController(GameRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/games")
    public Map<String, Object> search(@RequestParam String query) {
        if (query.isBlank() || query.length() > 100) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Укажите тему до 100 символов");
        }
        return Map.of("query", query, "games", repository.search(query.strip()));
    }

    @GetMapping("/games/latest")
    public Map<String, Object> latest(@RequestParam(defaultValue = "0", name = "after_id") long afterId,
                                      @RequestParam(defaultValue = "10") int limit) {
        if (afterId < 0 || limit < 1 || limit > 10) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Неверный курсор или размер страницы");
        }
        List<Map<String, Object>> games = repository.latest(afterId, limit);
        long latestReturnedId = games.isEmpty() ? afterId : ((Number) games.getFirst().get("id")).longValue();
        return Map.of("games", games, "latest_id", latestReturnedId, "count", games.size());
    }
}
