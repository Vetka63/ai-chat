"""SQLite-хранилище актуальной key-value памяти каждого диалога."""

from capabilities.context_memory.models import FactsState


class SqliteFactsRepository:
    """Хранит facts отдельно от оригинальных сообщений с каскадным удалением."""

    def __init__(self, store):
        self.store = store

    async def initialize(self):
        async with self.store.connection() as db:
            await db.execute('''CREATE TABLE IF NOT EXISTS facts (
                conversation_id TEXT PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
                agent_id TEXT NOT NULL, payload TEXT NOT NULL)''')
            await db.commit()

    async def get(self, agent_id: str, conversation_id: str) -> FactsState | None:
        await self.store.get(agent_id, conversation_id)
        async with self.store.connection() as db:
            row = await (await db.execute(
                'SELECT payload FROM facts WHERE conversation_id=? AND agent_id=?',
                (conversation_id, agent_id),
            )).fetchone()
        return FactsState.model_validate_json(row['payload']) if row else None

    async def save(self, agent_id: str, conversation_id: str, state: FactsState) -> None:
        await self.store.get(agent_id, conversation_id)
        async with self.store.connection() as db:
            await db.execute('''INSERT INTO facts VALUES(?,?,?) ON CONFLICT(conversation_id)
                DO UPDATE SET payload=excluded.payload WHERE facts.agent_id=excluded.agent_id''',
                (conversation_id, agent_id, state.model_dump_json()))
            await db.commit()
