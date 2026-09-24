"""Агент-владелец чата сводок: управляет расписанием через MCP."""

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
        if name not in {"create_watch", "get_watch", "cancel_watch", "list_reports", "delete_watch"}:
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


class GameDigestAgent:
    ID = "game_digest"

    def __init__(self, store, gateway: GameFeedGateway):
        self.store = store
        self.gateway = gateway

    @property
    def info(self) -> AgentInfo:
        return AgentInfo(id=self.ID, name="Игровые сводки",
                         description="Автоматически собирает каталог игр и хранит три последние сводки",
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
