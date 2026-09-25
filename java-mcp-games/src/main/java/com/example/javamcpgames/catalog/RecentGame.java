package com.example.javamcpgames.catalog;

/** Одна новая игра из SQL-каталога Java mock-сервиса. */
public record RecentGame(long id, String title, String genre, String description, String created_at) { }
