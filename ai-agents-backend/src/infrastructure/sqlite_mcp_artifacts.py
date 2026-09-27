"""Постоянное хранилище проверенных MCP-артефактов и связей между ними."""

import json

from agent_core.models import AgentError
from capabilities.mcp_orchestration.models import Artifact


class SqliteMcpArtifactRepository:
    def __init__(self, store):
        self.store = store

    async def initialize(self) -> None:
        async with self.store.connection() as db:
            await db.executescript("""
                CREATE TABLE IF NOT EXISTS mcp_artifacts (
                    id TEXT PRIMARY KEY,
                    agent_id TEXT NOT NULL,
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    kind TEXT NOT NULL CHECK(kind IN ('search', 'collection', 'summary', 'draft', 'report')),
                    title TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    source_ids_json TEXT NOT NULL,
                    user_index INTEGER NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS mcp_artifacts_chat
                    ON mcp_artifacts(agent_id, conversation_id, created_at, id);
            """)
            await db.commit()

    async def save(self, item: Artifact) -> Artifact:
        async with self.store.connection() as db:
            await db.execute("""INSERT INTO mcp_artifacts
                (id, agent_id, conversation_id, kind, title, payload_json, source_ids_json, user_index, created_at)
                VALUES(?,?,?,?,?,?,?,?,?)""", (
                item.id, item.agent_id, item.conversation_id, item.kind, item.title,
                json.dumps(item.payload, ensure_ascii=False), json.dumps(item.source_ids),
                item.user_index, item.created_at,
            ))
            await db.commit()
        return item

    async def list(self, agent_id: str, conversation_id: str) -> list[Artifact]:
        async with self.store.connection() as db:
            rows = await (await db.execute("""SELECT * FROM mcp_artifacts
                WHERE agent_id=? AND conversation_id=? ORDER BY created_at, rowid""",
                (agent_id, conversation_id))).fetchall()
        return [Artifact(
            id=row["id"], agent_id=row["agent_id"], conversation_id=row["conversation_id"],
            kind=row["kind"], title=row["title"], payload=json.loads(row["payload_json"]),
            source_ids=json.loads(row["source_ids_json"]), user_index=row["user_index"],
            created_at=row["created_at"],
        ) for row in rows]

    async def get(self, agent_id: str, conversation_id: str, artifact_id: str,
                  expected_kind: str | None = None) -> Artifact:
        async with self.store.connection() as db:
            row = await (await db.execute("""SELECT * FROM mcp_artifacts
                WHERE id=? AND agent_id=? AND conversation_id=?""",
                (artifact_id, agent_id, conversation_id))).fetchone()
        if row is None or (expected_kind is not None and row["kind"] != expected_kind):
            raise AgentError("artifact_not_found", "Артефакт этого чата не найден", 422)
        return Artifact(
            id=row["id"], agent_id=row["agent_id"], conversation_id=row["conversation_id"],
            kind=row["kind"], title=row["title"], payload=json.loads(row["payload_json"]),
            source_ids=json.loads(row["source_ids_json"]), user_index=row["user_index"],
            created_at=row["created_at"],
        )
