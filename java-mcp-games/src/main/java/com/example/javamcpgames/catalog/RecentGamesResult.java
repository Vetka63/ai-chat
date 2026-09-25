package com.example.javamcpgames.catalog;

import java.util.List;

/** Максимум десять последних новых игр и курсор самой новой записи. */
public record RecentGamesResult(long latest_id, int count, List<RecentGame> games) { }
