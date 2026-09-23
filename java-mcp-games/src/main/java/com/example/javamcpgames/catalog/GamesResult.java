package com.example.javamcpgames.catalog;

import java.util.List;

/** Нормализованный результат поиска для MCP-клиента и тестов. */
public record GamesResult(String query, List<Game> games) { }
