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
    @McpTool(name = "search_games", description = "Найти вымышленные игры по словам в названии или описании",
            generateOutputSchema = true)
    public GamesResult searchGames(@McpToolParam(description = "Название игры или тема, например космос", required = true)
                                   String query) {
        return catalog.search(query);
    }
}
