"""Адаптер сохранения сводки; запись заменяется атомарно только после успешного сжатия."""
from capabilities.context_memory.models import SummaryState


class SqliteSummaryRepository:
    """Один актуальный снимок summary на диалог, удаляемый вместе с этим диалогом."""
    def __init__(self, store):
        self.store = store

    async def initialize(self):
        async with self.store.connection() as db:
            await db.execute('''CREATE TABLE IF NOT EXISTS summaries (
                conversation_id TEXT PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
                agent_id TEXT NOT NULL, payload TEXT NOT NULL)''')
            await db.commit()

    async def get(self, agent_id, conversation_id):
        await self.store.get(agent_id, conversation_id)
        async with self.store.connection() as db:
            row = await (await db.execute('SELECT payload FROM summaries WHERE conversation_id=? AND agent_id=?',
                                         (conversation_id, agent_id))).fetchone()
        return SummaryState.model_validate_json(row['payload']) if row else None

    async def save(self, agent_id, conversation_id, state):
        await self.store.get(agent_id, conversation_id)
        async with self.store.connection() as db:
            await db.execute('''INSERT INTO summaries VALUES(?,?,?) ON CONFLICT(conversation_id)
                DO UPDATE SET payload=excluded.payload WHERE summaries.agent_id=excluded.agent_id''',
                (conversation_id, agent_id, state.model_dump_json()))
            await db.commit()
