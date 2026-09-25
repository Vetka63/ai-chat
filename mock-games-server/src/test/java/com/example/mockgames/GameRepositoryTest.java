package com.example.mockgames;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameRepositoryTest {
    @Test
    void keepsAllGeneratedGamesButReturnsTenForDigestAndPreservesSeedSearch() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:games-test;DB_CLOSE_DELAY=-1");
        GameRepository repository = new GameRepository(new JdbcTemplate(dataSource));
        for (int n = 1; n <= 12; n++) {
            repository.addGenerated("Игра " + n, "Приключение", "Описание " + n);
        }
        assertEquals(10, repository.latest(0, 10).size());
        assertEquals(12, repository.generatedCount());
        assertEquals(12L, repository.latestId());
        assertEquals(2, repository.latest(10, 10).size());
        assertEquals("Звёздные тропы", repository.search("космос").getFirst().get("title"));
        GamesController controller = new GamesController(repository);
        assertEquals(12L, controller.latest(0, 10).get("latest_id"));
        assertEquals(12L, controller.latest(12, 10).get("latest_id"));
    }
}
