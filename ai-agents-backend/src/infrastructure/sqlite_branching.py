"""Атомарное хранение checkpoint и создание независимых веток диалога."""

from agent_core.models import AgentError, ConversationSummary, new_id, now
from capabilities.context_memory.models import Checkpoint, ContextSettings


class SqliteBranchRepository:
    """Создаёт две дочерние беседы из неизменяемого снимка текущего конца истории."""

    def __init__(self, store):
        self.store = store

    async def initialize(self):
        async with self.store.connection() as db:
            await db.execute('''CREATE TABLE IF NOT EXISTS checkpoints (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                agent_id TEXT NOT NULL,
                title TEXT NOT NULL,
                message_count INTEGER NOT NULL,
                facts_payload TEXT,
                created_at TEXT NOT NULL)''')
            await db.execute('CREATE INDEX IF NOT EXISTS checkpoint_conversation ON checkpoints(agent_id, conversation_id, created_at)')
            await db.commit()

    async def create_checkpoint(self, agent_id: str, conversation_id: str, title: str) -> Checkpoint:
        conversation = await self.store.get(agent_id, conversation_id)
        item = Checkpoint(id=new_id(), agent_id=agent_id, conversation_id=conversation_id,
                          title=title.strip() or 'Checkpoint', message_count=len(conversation.messages), created_at=now())
        async with self.store.connection() as db:
            fact_row = await (await db.execute(
                'SELECT payload FROM facts WHERE conversation_id=? AND agent_id=?',
                (conversation_id, agent_id),
            )).fetchone()
            await db.execute('INSERT INTO checkpoints VALUES(?,?,?,?,?,?,?)',
                (item.id, item.conversation_id, item.agent_id, item.title, item.message_count,
                 fact_row['payload'] if fact_row else None, item.created_at))
            await db.commit()
        return item

    async def list_checkpoints(self, agent_id: str, conversation_id: str) -> list[Checkpoint]:
        await self.store.get(agent_id, conversation_id)
        async with self.store.connection() as db:
            rows = await (await db.execute('''SELECT id,agent_id,conversation_id,title,message_count,created_at
                FROM checkpoints WHERE agent_id=? AND conversation_id=? ORDER BY created_at,id''',
                (agent_id, conversation_id))).fetchall()
        return [Checkpoint.model_validate(dict(row)) for row in rows]

    async def create_branches(self, agent_id: str, checkpoint_id: str, names: list[str]) -> list[ConversationSummary]:
        if len(names) != 2 or len(set(names)) != 2:
            raise AgentError('invalid_branches', 'Нужны два разных названия веток')
        timestamp = now()
        async with self.store.connection() as db:
            await db.execute('BEGIN IMMEDIATE')
            checkpoint = await (await db.execute(
                'SELECT * FROM checkpoints WHERE id=? AND agent_id=?', (checkpoint_id, agent_id))).fetchone()
            if checkpoint is None:
                raise AgentError('checkpoint_not_found', 'Checkpoint не найден', 404)
            existing = await (await db.execute(
                'SELECT count(*) AS total FROM conversations WHERE checkpoint_id=? AND agent_id=?',
                (checkpoint_id, agent_id),
            )).fetchone()
            if existing['total']:
                raise AgentError('branches_already_created', 'Для этого checkpoint две ветки уже созданы', 409)
            parent = await (await db.execute(
                '''SELECT id,agent_id,title,selected_model_id,context_settings,max_output_tokens,root_conversation_id
                   FROM conversations WHERE id=? AND agent_id=?''',
                (checkpoint['conversation_id'], agent_id))).fetchone()
            if parent is None:
                raise AgentError('conversation_not_found', 'Исходный диалог не найден', 404)
            rows = await (await db.execute(
                'SELECT role,content,created_at FROM messages WHERE conversation_id=? ORDER BY sequence LIMIT ?',
                (parent['id'], checkpoint['message_count']))).fetchall()
            settings = ContextSettings.model_validate_json(parent['context_settings']).model_copy(update={'mode': 'branching'})
            root_id = parent['root_conversation_id'] or parent['id']
            created = []
            for name in names:
                item = ConversationSummary(
                    id=new_id(), agent_id=agent_id, title=name, created_at=timestamp, updated_at=timestamp,
                    selected_model_id=parent['selected_model_id'], max_output_tokens=parent['max_output_tokens'],
                    context_settings=settings, root_conversation_id=root_id,
                    parent_conversation_id=parent['id'], checkpoint_id=checkpoint_id, branch_name=name,
                )
                await db.execute('''INSERT INTO conversations(id,agent_id,title,created_at,updated_at,selected_model_id,
                    context_settings,max_output_tokens,root_conversation_id,parent_conversation_id,checkpoint_id,branch_name)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)''',
                    (item.id,item.agent_id,item.title,item.created_at,item.updated_at,item.selected_model_id,
                     settings.model_dump_json(),item.max_output_tokens,item.root_conversation_id,
                     item.parent_conversation_id,item.checkpoint_id,item.branch_name))
                await db.executemany('INSERT INTO messages(conversation_id,role,content,created_at) VALUES(?,?,?,?)',
                    [(item.id,row['role'],row['content'],row['created_at']) for row in rows])
                if checkpoint['facts_payload']:
                    await db.execute('INSERT INTO facts(conversation_id,agent_id,payload) VALUES(?,?,?)',
                                     (item.id, agent_id, checkpoint['facts_payload']))
                created.append(item)
            await db.commit()
        return created
