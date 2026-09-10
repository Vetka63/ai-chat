"""Независимое хранение запусков и метрик в SQLite-базе диалогов."""
from capabilities.token_accounting.models import RunRecord


class SqliteUsageRepository:
    """Сохраняет снимки запусков; удаление диалога каскадно удаляет метрики."""
    def __init__(self, store):
        self.store = store

    async def initialize(self):
        async with self.store.connection() as db:
            await db.execute("""CREATE TABLE IF NOT EXISTS runs (
                id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                agent_id TEXT NOT NULL, created_at TEXT NOT NULL, payload TEXT NOT NULL)""")
            await db.execute("CREATE INDEX IF NOT EXISTS runs_conversation ON runs(agent_id, conversation_id, created_at)")
            rows = await (await db.execute("SELECT id, payload FROM runs WHERE json_extract(payload, '$.status')='pending'")).fetchall()
            for row in rows:
                run = RunRecord.model_validate_json(row["payload"])
                run.status, run.error_code = "interrupted", "app_restarted"
                run.error_message = "Приложение перезапущено до записи результата. Расход неизвестен."
                await db.execute("UPDATE runs SET payload=? WHERE id=?", (run.model_dump_json(), run.id))
            await db.commit()

    async def save(self, record: RunRecord):
        async with self.store.connection() as db:
            await db.execute("""INSERT INTO runs VALUES(?,?,?,?,?) ON CONFLICT(id)
                DO UPDATE SET payload=excluded.payload""",
                (record.id, record.conversation_id, record.agent_id, record.created_at, record.model_dump_json()))
            await db.commit()

    async def list(self, agent_id, conversation_id):
        await self.store.get(agent_id, conversation_id)
        async with self.store.connection() as db:
            rows = await (await db.execute("SELECT payload FROM runs WHERE agent_id=? AND conversation_id=? ORDER BY created_at, id",
                (agent_id, conversation_id))).fetchall()
        return [RunRecord.model_validate_json(row["payload"]) for row in rows]
