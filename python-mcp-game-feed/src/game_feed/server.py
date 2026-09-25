"""HTTP MCP-сервер: подписки и сохранение результатов, но не каталог игр."""

import os
from typing import Any

from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError

from game_feed.repository import FeedRepository


def create_server(repository: FeedRepository) -> MCPServer:
    mcp = MCPServer("Game Feed")

    @mcp.tool()
    async def create_watch(conversation_id: str, interval_seconds: int) -> dict[str, Any]:
        """Запустить периодическую сводку для чата.

        Args:
            conversation_id: ID чата агента игровых сводок.
            interval_seconds: Интервал сводки в секундах; UI задаёт 1800.
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
    async def claim_due(limit: int = 10) -> dict[str, Any]:
        """Выдать агентному планировщику наступившие подписки."""
        if not 1 <= limit <= 100:
            raise ToolError("Лимит должен быть от 1 до 100")
        return {"watches": repository.claim_due(limit)}

    @mcp.tool()
    async def release_failed(watch_id: str, scheduled_for: str) -> dict[str, Any]:
        """Вернуть неудавшийся запуск в очередь для безопасного повтора."""
        repository.release_failed(watch_id, scheduled_for)
        return {"released": True}

    @mcp.tool()
    async def save_digest(watch_id: str, scheduled_for: str, latest_id: int,
                          games: list[dict[str, Any]], text: str) -> dict[str, Any]:
        """Сохранить сводку агента из максимум десяти новых игр в SQLite."""
        try:
            return {"report": repository.save_digest(watch_id, scheduled_for, latest_id, games, text)}
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
