package com.example.javamcpgames.catalog;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Публичная MCP-граница: Spring AI регистрирует только этот инструмент. */
@Component
public class GamesTools {

    private final GamesCatalogClient catalog;

    public GamesTools(GamesCatalogClient catalog) {
        this.catalog = catalog;
    }

    /** Возвращает те же query и games, что и Python-реализация Дня 17. */
    @McpTool(name = "search_games", description = "Поиск только в нашем учебном каталоге вымышленных игр. "
            + "Используй при явной просьбе проверить каталог или когда пользователь спрашивает "
            + "о конкретной игре, о которой у тебя нет надёжных сведений. "
            + "Во втором случае не требуй слова «каталог». "
            + "Не используй для общих вопросов, известных тебе игр, советов или идей. "
            + "Отсутствие игры здесь не доказывает, что она не существует вообще. "
            + "Возвращает только query и список игр с названием и описанием.",
            generateOutputSchema = true)
    public GamesResult searchGames(@McpToolParam(description = "Название игры или ключевые слова для поиска в каталоге", required = true)
                                   String query) {
        return catalog.search(query);
    }

    /** Инструмент агента дня 18: не обрезает SQL-каталог, а ограничивает одну сводку. */
    @McpTool(name = "get_new_games", description = "Получить до десяти самых новых игр, созданных после afterId. "
            + "Игры хранятся в Java mock-сервисе; если за период их больше десяти, в сводку попадают последние десять.",
            generateOutputSchema = true)
    public RecentGamesResult getNewGames(
            @McpToolParam(description = "ID последней игры предыдущей успешной сводки", required = true) long afterId,
            @McpToolParam(description = "Максимум игр в новой сводке, от 1 до 10", required = true) int limit) {
        return catalog.newGames(afterId, limit);
    }
}
