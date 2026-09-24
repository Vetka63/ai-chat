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
}
