"""HTTP MCP-сервер: управление подписками и выполнение одного сбора."""

import os
from typing import Any

from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError

from game_feed.repository import FeedRepository
from game_feed.source import GeneratedGameSource, GameSource


def create_server(repository: FeedRepository, source: GameSource | None = None) -> MCPServer:
    mcp = MCPServer("Game Feed")
    game_source = source or GeneratedGameSource()

    @mcp.tool()
    async def create_watch(conversation_id: str, interval_seconds: int) -> dict[str, Any]:
        """Запустить периодическую сводку для чата.

        Args:
            conversation_id: ID чата агента игровых сводок.
            interval_seconds: Интервал от 60 до 86400 секунд.
        """
        if not conversation_id or len(conversation_id) > 64:
            raise ToolError("Некорректный идентификатор чата")
        try:
            return {"watch": repository.create_watch(conversation_id, interval_seconds)}
        except ValueError as exc:
            raise ToolError(str(exc)) from exc

    @mcp.tool()
    async def get_watch(conversation_id: str) -> dict[str, Any]:
        """Получить статус и следующее время запуска подписки чата."""
        return {"watch": repository.get_watch(conversation_id)}

    @mcp.tool()
    async def cancel_watch(conversation_id: str) -> dict[str, Any]:
        """Остановить подписку чата, не удаляя её последние сводки."""
        return {"watch": repository.cancel_watch(conversation_id)}

    @mcp.tool()
    async def list_reports(conversation_id: str) -> dict[str, Any]:
        """Получить до трёх последних сводок чата, созданных по расписанию."""
        return {"reports": repository.list_reports(conversation_id)}

    @mcp.tool()
    async def delete_watch(conversation_id: str) -> dict[str, Any]:
        """Удалить подписку и отчёты удаляемого чата, сохранив общий каталог игр."""
        return repository.delete_watch(conversation_id)

    @mcp.tool()
    async def collect_games(watch_id: str, scheduled_for: str) -> dict[str, Any]:
        """Выполнить один зарезервированный запуск; инструмент вызывает worker.

        Args:
            watch_id: ID подписки, созданной инструментом create_watch.
            scheduled_for: UTC-время зарезервированного запуска в формате ISO 8601.
        """
        try:
            return {"report": repository.collect(watch_id, scheduled_for, game_source)}
        except ValueError as exc:
            raise ToolError(str(exc)) from exc

    return mcp


def main() -> None:
    repository = FeedRepository(os.environ.get("GAME_FEED_DB", "data/game-feed.sqlite3"))
    create_server(repository).run(
        transport="streamable-http", host="0.0.0.0", port=int(os.environ.get("PORT", "8080")),
        streamable_http_path="/mcp", stateless_http=True, json_response=True,
    )


if __name__ == "__main__":
    main()
