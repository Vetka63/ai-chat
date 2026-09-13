"""Постоянное SQLite-хранилище разговоров и сообщений."""

from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal

import aiosqlite

from agent_core.models import AgentError, Conversation, ConversationSummary, Message, new_id, now
from capabilities.context_memory.models import ContextSettings


class SqliteConversationStore:
    """Изолирует диалоги по agent_id и независимо сохраняет каждое сообщение."""

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
            columns = await (await database.execute("PRAGMA table_info(conversations)")).fetchall()
            if "selected_model_id" not in {column["name"] for column in columns}:
                await database.execute("ALTER TABLE conversations ADD COLUMN selected_model_id TEXT")
            if "context_settings" not in {column["name"] for column in columns}:
                await database.execute("ALTER TABLE conversations ADD COLUMN context_settings TEXT NOT NULL DEFAULT '{}'")
            if "max_output_tokens" not in {column["name"] for column in columns}:
                await database.execute("ALTER TABLE conversations ADD COLUMN max_output_tokens INTEGER")
            if "root_conversation_id" not in {column["name"] for column in columns}:
                await database.execute("ALTER TABLE conversations ADD COLUMN root_conversation_id TEXT")
            if "parent_conversation_id" not in {column["name"] for column in columns}:
                await database.execute("ALTER TABLE conversations ADD COLUMN parent_conversation_id TEXT")
            if "checkpoint_id" not in {column["name"] for column in columns}:
                await database.execute("ALTER TABLE conversations ADD COLUMN checkpoint_id TEXT")
            if "branch_name" not in {column["name"] for column in columns}:
                await database.execute("ALTER TABLE conversations ADD COLUMN branch_name TEXT")
            await database.execute("UPDATE conversations SET root_conversation_id=id WHERE root_conversation_id IS NULL")
            await database.commit()

    async def create(self, agent_id: str, title: str, settings: ContextSettings | None = None) -> ConversationSummary:
        """Атомарно создаёт чат вместе с неизменяемой стратегией контекста."""

        timestamp = now()
        context_settings = settings or ContextSettings()
        item = ConversationSummary(
            id=new_id(),
            agent_id=agent_id,
            title=title.strip() or "Новый чат",
            created_at=timestamp,
            updated_at=timestamp,
            context_settings=context_settings,
        )
        item.root_conversation_id = item.id
        async with self.connection() as database:
            await database.execute(
                """INSERT INTO conversations(id, agent_id, title, created_at, updated_at, context_settings, root_conversation_id)
                   VALUES(?,?,?,?,?,?,?)""",
                (item.id, item.agent_id, item.title, item.created_at, item.updated_at,
                 context_settings.model_dump_json(), item.root_conversation_id),
            )
            await database.commit()
        return item

    async def list(self, agent_id: str) -> list[ConversationSummary]:
        async with self.connection() as database:
            rows = await (
                await database.execute(
                    """SELECT id, agent_id, title, created_at, updated_at, selected_model_id, context_settings, max_output_tokens,
                              root_conversation_id, parent_conversation_id, checkpoint_id, branch_name
                       FROM conversations WHERE agent_id=? ORDER BY updated_at DESC, id DESC""",
                    (agent_id,),
                )
            ).fetchall()
        return [ConversationSummary.model_validate(self.decode(row)) for row in rows]

    async def get(self, agent_id: str, conversation_id: str) -> Conversation:
        async with self.connection() as database:
            row = await (
                await database.execute(
                    """SELECT id, agent_id, title, created_at, updated_at, selected_model_id, context_settings, max_output_tokens,
                              root_conversation_id, parent_conversation_id, checkpoint_id, branch_name
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
            **self.decode(row),
            messages=[Message.model_validate(dict(message)) for message in message_rows],
        )

    @staticmethod
    def decode(row):
        """Читает настройки старых и новых разговоров без изменения истории."""
        result = dict(row)
        result['context_settings'] = ContextSettings.model_validate_json(result['context_settings'])
        return result

    async def configure_output(self, agent_id: str, conversation_id: str, max_tokens: int | None):
        """Сохраняет лимит следующего ответа; None оставляет выбор провайдеру."""
        async with self.connection() as db:
            cursor = await db.execute('UPDATE conversations SET max_output_tokens=? WHERE id=? AND agent_id=?',
                                      (max_tokens, conversation_id, agent_id))
            if cursor.rowcount == 0:
                raise AgentError('conversation_not_found', 'Диалог не найден', 404)
            await db.commit()

    async def configure_context(self, agent_id: str, conversation_id: str, settings: ContextSettings):
        """Сохраняет стратегию; старые сообщения и сводки не удаляются."""
        async with self.connection() as db:
            cursor = await db.execute('UPDATE conversations SET context_settings=? WHERE id=? AND agent_id=?',
                                     (settings.model_dump_json(), conversation_id, agent_id))
            if cursor.rowcount == 0:
                raise AgentError('conversation_not_found', 'Диалог не найден', 404)
            await db.commit()

    async def fork(self, source: Conversation, settings: ContextSettings) -> ConversationSummary:
        """Копирует снимок исходной переписки без прежних затрат и summary для честного сравнения."""
        label = 'Сжатая · ' if settings.mode == 'summary' else 'Полная · '
        item = ConversationSummary(id=new_id(), agent_id=source.agent_id, title=(label + source.title)[:120],
            created_at=now(), updated_at=now(), selected_model_id=source.selected_model_id,
            context_settings=settings, max_output_tokens=source.max_output_tokens)
        item.root_conversation_id = item.id
        async with self.connection() as db:
            await db.execute('''INSERT INTO conversations(id,agent_id,title,created_at,updated_at,selected_model_id,
                context_settings,max_output_tokens,root_conversation_id) VALUES(?,?,?,?,?,?,?,?,?)''',
                (item.id, item.agent_id, item.title, item.created_at, item.updated_at,
                 item.selected_model_id, settings.model_dump_json(), item.max_output_tokens, item.root_conversation_id))
            await db.executemany('INSERT INTO messages(conversation_id,role,content,created_at) VALUES(?,?,?,?)',
                [(item.id, m.role, m.content, item.created_at) for m in source.messages])
            await db.commit()
        return item

    async def delete(self, agent_id: str, conversation_id: str) -> None:
        async with self.connection() as database:
            cursor = await database.execute(
                "DELETE FROM conversations WHERE id=? AND agent_id=?",
                (conversation_id, agent_id),
            )
            if cursor.rowcount == 0:
                raise AgentError("conversation_not_found", "Диалог не найден у выбранного агента", 404)
            await database.commit()

    async def select_model(self, agent_id: str, conversation_id: str, model_id: str) -> None:
        """Запоминает модель для следующих сообщений, не меняя старые ответы."""
        async with self.connection() as db:
            cursor = await db.execute("UPDATE conversations SET selected_model_id=? WHERE id=? AND agent_id=?",
                                     (model_id, conversation_id, agent_id))
            if cursor.rowcount == 0:
                raise AgentError("conversation_not_found", "Диалог не найден", 404)
            await db.commit()

    async def append_message(
        self,
        agent_id: str,
        conversation_id: str,
        role: Literal["user", "assistant"],
        content: str,
    ) -> None:
        """Сохраняет сообщение сразу, чтобы вопрос пользователя не терялся при сбое LLM."""

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
            await database.execute(
                "INSERT INTO messages(conversation_id, role, content, created_at) VALUES(?,?,?,?)",
                (conversation_id, role, content, timestamp),
            )
            title = row["title"]
            if role == "user" and title == "Новый чат":
                title = " ".join(content.split())[:60] or title
            await database.execute(
                "UPDATE conversations SET title=?, updated_at=? WHERE id=?",
                (title, timestamp, conversation_id),
            )
            await database.commit()

