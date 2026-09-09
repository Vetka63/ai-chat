"""Постоянное SQLite-хранилище разговоров и сообщений."""

from contextlib import asynccontextmanager
from pathlib import Path

import aiosqlite

from agent_core.models import AgentError, Conversation, ConversationSummary, Message, new_id, now


class SqliteConversationStore:
    """Изолирует диалоги по agent_id и сохраняет каждую успешную пару сообщений."""

    def __init__(self, path: Path):
        self.path = path

    @asynccontextmanager
    async def connection(self):
        async with aiosqlite.connect(self.path, timeout=15) as database:
            database.row_factory = aiosqlite.Row
            await database.execute("PRAGMA foreign_keys=ON")
            yield database

    async def initialize(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        async with self.connection() as database:
            await database.execute("PRAGMA journal_mode=WAL")
            await database.executescript(
                """
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY,
                    agent_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS conversations_agent_updated
                    ON conversations(agent_id, updated_at DESC);
                CREATE TABLE IF NOT EXISTS messages (
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS messages_conversation_sequence
                    ON messages(conversation_id, sequence);
                """
            )
            await database.commit()

    async def create(self, agent_id: str, title: str) -> ConversationSummary:
        timestamp = now()
        item = ConversationSummary(
            id=new_id(),
            agent_id=agent_id,
            title=title.strip() or "Новый чат",
            created_at=timestamp,
            updated_at=timestamp,
        )
        async with self.connection() as database:
            await database.execute(
                "INSERT INTO conversations(id, agent_id, title, created_at, updated_at) VALUES(?,?,?,?,?)",
                (item.id, item.agent_id, item.title, item.created_at, item.updated_at),
            )
            await database.commit()
        return item

    async def list(self, agent_id: str) -> list[ConversationSummary]:
        async with self.connection() as database:
            rows = await (
                await database.execute(
                    """SELECT id, agent_id, title, created_at, updated_at
                       FROM conversations WHERE agent_id=? ORDER BY updated_at DESC, id DESC""",
                    (agent_id,),
                )
            ).fetchall()
        return [ConversationSummary.model_validate(dict(row)) for row in rows]

    async def get(self, agent_id: str, conversation_id: str) -> Conversation:
        async with self.connection() as database:
            row = await (
                await database.execute(
                    """SELECT id, agent_id, title, created_at, updated_at
                       FROM conversations WHERE id=? AND agent_id=?""",
                    (conversation_id, agent_id),
                )
            ).fetchone()
            if row is None:
                raise AgentError("conversation_not_found", "Диалог не найден у выбранного агента", 404)
            message_rows = await (
                await database.execute(
                    "SELECT role, content FROM messages WHERE conversation_id=? ORDER BY sequence",
                    (conversation_id,),
                )
            ).fetchall()
        return Conversation(
            **dict(row),
            messages=[Message.model_validate(dict(message)) for message in message_rows],
        )

    async def delete(self, agent_id: str, conversation_id: str) -> None:
        async with self.connection() as database:
            cursor = await database.execute(
                "DELETE FROM conversations WHERE id=? AND agent_id=?",
                (conversation_id, agent_id),
            )
            if cursor.rowcount == 0:
                raise AgentError("conversation_not_found", "Диалог не найден у выбранного агента", 404)
            await database.commit()

    async def append_exchange(
        self,
        agent_id: str,
        conversation_id: str,
        user_message: str,
        assistant_message: str,
    ) -> None:
        timestamp = now()
        async with self.connection() as database:
            await database.execute("BEGIN IMMEDIATE")
            row = await (
                await database.execute(
                    "SELECT title FROM conversations WHERE id=? AND agent_id=?",
                    (conversation_id, agent_id),
                )
            ).fetchone()
            if row is None:
                raise AgentError("conversation_not_found", "Диалог не найден у выбранного агента", 404)
            await database.executemany(
                "INSERT INTO messages(conversation_id, role, content, created_at) VALUES(?,?,?,?)",
                [
                    (conversation_id, "user", user_message, timestamp),
                    (conversation_id, "assistant", assistant_message, timestamp),
                ],
            )
            title = row["title"]
            if title == "Новый чат":
                title = " ".join(user_message.split())[:60] or title
            await database.execute(
                "UPDATE conversations SET title=?, updated_at=? WHERE id=?",
                (title, timestamp, conversation_id),
            )
            await database.commit()

