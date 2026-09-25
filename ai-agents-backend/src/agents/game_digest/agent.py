"""Агент сводок: по сигналу планировщика получает новые игры и сохраняет итог."""

import asyncio
import logging

from mcp import Client

from agent_core.models import AgentError, AgentInfo

logger = logging.getLogger(__name__)


class GameFeedGateway:
    """Знает доверенный адрес MCP; браузер не передаёт URL или имя инструмента."""

    def __init__(self, url: str):
        self.url = url

    async def call(self, name: str, arguments: dict):
        if name not in {"create_watch", "get_watch", "cancel_watch", "list_reports",
                        "delete_watch", "claim_due", "release_failed", "save_digest"}:
            raise ValueError("Инструмент не разрешён агенту")
        try:
            async with asyncio.timeout(12):
                async with Client(self.url) as client:
                    result = await client.call_tool(name, arguments)
            if result.is_error or not isinstance(result.structured_content, dict):
                raise RuntimeError("MCP вернул ошибку")
            return result.structured_content
        except Exception as exc:
            logger.exception("game_digest_mcp_failed tool=%s", name)
            raise AgentError("game_feed_unavailable", "Сервис игровых сводок сейчас недоступен", 502) from exc


class GameCatalogGateway:
    """Через Java MCP получает только новые игры из отдельного SQL mock-сервиса."""

    def __init__(self, url: str):
        self.url = url

    async def new_games(self, after_id: int) -> dict:
        try:
            async with asyncio.timeout(12):
                async with Client(self.url) as client:
                    result = await client.call_tool("get_new_games", {"afterId": after_id, "limit": 10})
            if result.is_error or not isinstance(result.structured_content, dict):
                raise RuntimeError("Java MCP вернул ошибку")
            return result.structured_content
        except Exception as exc:
            logger.exception("game_digest_catalog_failed after_id=%s", after_id)
            raise AgentError("game_catalog_unavailable", "Не удалось получить новые игры через MCP", 502) from exc


class GameDigestAgent:
    ID = "game_digest"

    def __init__(self, store, gateway: GameFeedGateway, catalog: GameCatalogGateway):
        self.store = store
        self.gateway = gateway
        self.catalog = catalog

    @property
    def info(self) -> AgentInfo:
        return AgentInfo(id=self.ID, name="Игровые сводки",
                         description="Раз в 30 минут показывает до 10 новых игр из SQL-каталога",
                         capabilities=["persistent_history", "scheduled_reports"])

    async def create_conversation(self, title, context_settings=None, problem=None, profile_id=None):
        if problem is not None or profile_id is not None:
            raise AgentError("invalid_digest_chat", "Для сводок не нужны карточка задачи и профиль")
        if context_settings is not None and context_settings.mode != "full":
            raise AgentError("invalid_digest_context", "Сводки используют отдельный чат без стратегии контекста")
        return await self.store.create(self.ID, title, context_settings)

    async def run(self, command):
        raise AgentError("digest_read_only", "Этот чат наполняется по расписанию. Управляйте подпиской в панели сводок.", 422)

    async def _check(self, conversation_id: str):
        await self.store.get(self.ID, conversation_id)

    async def status(self, conversation_id: str):
        await self._check(conversation_id)
        return await self.gateway.call("get_watch", {"conversation_id": conversation_id})

    async def schedule(self, conversation_id: str, interval_seconds: int):
        await self._check(conversation_id)
        return await self.gateway.call("create_watch", {
            "conversation_id": conversation_id, "interval_seconds": interval_seconds,
        })

    async def reports(self, conversation_id: str):
        await self._check(conversation_id)
        return await self.gateway.call("list_reports", {"conversation_id": conversation_id})

    async def cancel(self, conversation_id: str):
        await self._check(conversation_id)
        return await self.gateway.call("cancel_watch", {"conversation_id": conversation_id})

    async def delete_conversation(self, conversation_id: str):
        await self._check(conversation_id)
        await self.gateway.call("delete_watch", {"conversation_id": conversation_id})

    @staticmethod
    def _summary(games: list[dict]) -> str:
        """Формирует проверяемую сводку строго из полученных MCP-данных."""
        if not games:
            return "С прошлого выпуска новых игр пока не появилось."
        lines = [f"Новых игр в сводке: {len(games)}. Показаны самые новые:"]
        for index, game in enumerate(games, 1):
            lines.append(f"{index}. {game['title']} ({game['genre']}) — {game['description']}")
        return "\n".join(lines)

    async def run_scheduled(self, watch: dict) -> dict:
        """Один запуск: проверить чат → Java MCP → сводка → сохранить через MCP."""
        await self._check(watch["conversation_id"])
        after_id = int(watch.get("last_game_id") or 0)
        batch = await self.catalog.new_games(after_id)
        games = batch.get("games")
        latest_id = batch.get("latest_id")
        if not isinstance(games, list) or len(games) > 10 or not isinstance(latest_id, int):
            raise AgentError("invalid_game_batch", "MCP вернул некорректный список игр", 502)
        if latest_id < after_id or any(
            not isinstance(game, dict) or not isinstance(game.get("id"), int)
            or game["id"] <= after_id or not all(isinstance(game.get(key), str) for key in
                                                 ("title", "genre", "description", "created_at"))
            for game in games
        ):
            raise AgentError("invalid_game_batch", "MCP вернул некорректные данные игр", 502)
        result = await self.gateway.call("save_digest", {
            "watch_id": watch["id"], "scheduled_for": watch["scheduled_for"],
            "latest_id": latest_id, "games": games, "text": self._summary(games),
        })
        return result["report"]

    async def run_due(self) -> int:
        """Планировщик будит агента; MCP-вызовы делает именно агент."""
        due = await self.gateway.call("claim_due", {"limit": 10})
        watches = due.get("watches", [])
        for watch in watches:
            try:
                await self.run_scheduled(watch)
                logger.info("game_digest_saved watch_id=%s scheduled_for=%s",
                            watch["id"], watch["scheduled_for"])
            except Exception:
                logger.exception("game_digest_failed watch_id=%s", watch.get("id"))
                try:
                    await self.gateway.call("release_failed", {
                        "watch_id": watch["id"], "scheduled_for": watch["scheduled_for"],
                    })
                except Exception:
                    logger.exception("game_digest_release_failed watch_id=%s", watch.get("id"))
        return len(watches)
