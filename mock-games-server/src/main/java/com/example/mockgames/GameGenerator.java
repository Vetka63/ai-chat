package com.example.mockgames;

import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Каждые 20 секунд пополняет SQL-базу одной новой вымышленной игрой. */
@Component
public class GameGenerator {
    private static final String[] PLACES = {"Лунный", "Лесной", "Океанский", "Облачный", "Пиксельный"};
    private static final String[] OBJECTS = {"экспресс", "сад", "архив", "маяк", "лабиринт", "театр"};
    private static final String[] GENRES = {"Приключение", "Стратегия", "Головоломка", "Симулятор"};
    private final GameRepository repository;
    private final boolean enabled;

    public GameGenerator(GameRepository repository, @Value("${games.generation.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${games.generation.interval-ms:20000}",
               initialDelayString = "${games.generation.initial-delay-ms:0}")
    public void generate() {
        if (!enabled) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String title = PLACES[random.nextInt(PLACES.length)] + " "
                + OBJECTS[random.nextInt(OBJECTS.length)] + " · " + System.currentTimeMillis();
        String genre = GENRES[random.nextInt(GENRES.length)];
        repository.addGenerated(title, genre, "Новая вымышленная игра жанра «" + genre
                + "»: исследуйте мир, принимайте решения и открывайте неожиданные истории.");
    }
}
