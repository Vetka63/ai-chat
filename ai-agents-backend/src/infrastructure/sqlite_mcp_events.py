"""Отдельное SQLite-хранилище следов MCP-вызовов в диалогах."""

import json

from capabilities.mcp_discovery.models import McpToolEvent


class SqliteMcpEventRepository:
    """Сохраняет аргументы и результаты инструментов отдельно от сообщений."""

    def __init__(self, store):
        self.store = store

    async def initialize(self) -> None:
        """Создаёт таблицу, записи которой удаляются вместе с чатом."""

        async with self.store.connection() as db:
            await db.executescript("""
                CREATE TABLE IF NOT EXISTS mcp_tool_events (
                    id TEXT PRIMARY KEY,
                    agent_id TEXT NOT NULL,
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    user_index INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    server_id TEXT NOT NULL,
                    tool_name TEXT NOT NULL,
                    arguments_json TEXT NOT NULL,
                    result_json TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('success', 'error'))
                );
                CREATE INDEX IF NOT EXISTS mcp_events_conversation
                    ON mcp_tool_events(conversation_id, created_at);
            """)
            await db.commit()

    async def record(self, event: McpToolEvent) -> None:
        """Атомарно фиксирует один результат MCP-вызова."""

        async with self.store.connection() as db:
            await db.execute("""INSERT INTO mcp_tool_events
                (id, agent_id, conversation_id, user_index, created_at, server_id, tool_name,
                 arguments_json, result_json, status) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                (event.id, event.agent_id, event.conversation_id, event.user_index, event.created_at,
                 event.server_id, event.tool_name, json.dumps(event.arguments, ensure_ascii=False),
                 json.dumps(event.result, ensure_ascii=False), event.status))
            await db.commit()

    async def list(self, agent_id: str, conversation_id: str) -> list[McpToolEvent]:
        """Возвращает следы только выбранного агента и диалога в порядке вызова."""

        async with self.store.connection() as db:
            rows = await (await db.execute("""SELECT * FROM mcp_tool_events
                WHERE agent_id=? AND conversation_id=? ORDER BY created_at, id""",
                (agent_id, conversation_id))).fetchall()
        return [McpToolEvent(
            id=row["id"], agent_id=row["agent_id"], conversation_id=row["conversation_id"],
            user_index=row["user_index"], created_at=row["created_at"], server_id=row["server_id"],
            tool_name=row["tool_name"], arguments=json.loads(row["arguments_json"]),
            result=json.loads(row["result_json"]), status=row["status"],
        ) for row in rows]
