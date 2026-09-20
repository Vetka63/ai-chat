"""SQLite-адаптер обязательных правил и аудита, отделённый от переписки."""
from agent_core.models import AgentError, new_id, now
from capabilities.invariants.models import InvariantCheck, InvariantWorkspace, TaskInvariant
from infrastructure.migrations import migrate_memory_layers


class SqliteInvariantRepository:
    """Ограничивает правила конкретной задачей и проверяет версии при записи."""

    def __init__(self, store):
        self.store = store

    async def initialize(self):
        await migrate_memory_layers(self.store)

    async def _task(self, db, agent_id, conversation_id):
        row = await (await db.execute('''SELECT t.id FROM tasks t
            JOIN conversations c ON c.id=t.conversation_id
            WHERE t.agent_id=? AND c.agent_id=? AND t.conversation_id=?''',
            (agent_id, agent_id, conversation_id))).fetchone()
        if row is None:
            raise AgentError('task_not_found', 'Задача не найдена у выбранного агента', 404)
        return row['id']

    async def _read(self, db, task_id):
        version = await (await db.execute('SELECT revision FROM task_invariant_sets WHERE task_id=?',
            (task_id,))).fetchone()
        rules = await (await db.execute('''SELECT id,kind,label,value,active,revision
            FROM task_invariant_rules WHERE task_id=? ORDER BY rowid''', (task_id,))).fetchall()
        checks = await (await db.execute('''SELECT payload FROM task_invariant_checks
            WHERE task_id=? ORDER BY rowid DESC LIMIT 30''', (task_id,))).fetchall()
        return InvariantWorkspace(revision=version['revision'] if version else 1,
            rules=[TaskInvariant(**dict(rule)) for rule in rules],
            checks=[InvariantCheck.model_validate_json(row['payload']) for row in reversed(checks)])

    async def workspace(self, agent_id, conversation_id):
        async with self.store.connection() as db:
            await db.execute('BEGIN')
            task_id = await self._task(db, agent_id, conversation_id)
            return await self._read(db, task_id)

    async def save(self, agent_id, conversation_id, command):
        """До принятия плана разрешено явно менять правила; изменение отменяет кандидата."""
        async with self.store.connection() as db:
            await db.execute('BEGIN IMMEDIATE')
            task_id = await self._task(db, agent_id, conversation_id)
            state = await (await db.execute('SELECT phase,status,revision FROM task_workflow WHERE task_id=?',
                (task_id,))).fetchone()
            if state is None or state['phase'] != 'planning' or state['status'] != 'active':
                raise AgentError('invariants_locked', 'Правила можно менять только во время активного планирования', 409)
            previous = await self._read(db, task_id)
            if command.expected_revision != previous.revision:
                raise AgentError('state_conflict', 'Правила изменились. Обновите панель', 409)
            existing = {rule.id: rule for rule in previous.rules}
            supplied = [rule.id for rule in command.rules if rule.id]
            if len(supplied) != len(set(supplied)) or any(rule_id not in existing for rule_id in supplied):
                raise AgentError('invalid_invariants', 'ID правила повторён или не принадлежит этой задаче', 422)
            # Полный список заменяется атомарно; аудит сохраняет снимки прежних правил.
            prepared = [TaskInvariant(**draft.model_dump(exclude={'id'}),
                id=draft.id or new_id(), revision=existing[draft.id].revision + 1 if draft.id else 1)
                for draft in command.rules]
            if len(prepared) == len(previous.rules) and all(
                current.id == old.id and current.kind == old.kind and current.label == old.label
                and current.value == old.value and current.active == old.active
                for current, old in zip(prepared, previous.rules)):
                return previous
            await db.execute('DELETE FROM task_invariant_rules WHERE task_id=?', (task_id,))
            await db.executemany('''INSERT INTO task_invariant_rules(id,task_id,kind,label,value,active,revision)
                VALUES(?,?,?,?,?,?,?)''', [(rule.id, task_id, rule.kind, rule.label, rule.value,
                    int(rule.active), rule.revision) for rule in prepared])
            await db.execute('''INSERT INTO task_invariant_sets(task_id,revision) VALUES(?,?)
                ON CONFLICT(task_id) DO UPDATE SET revision=excluded.revision''',
                (task_id, previous.revision + 1))
            await db.execute('''UPDATE task_workflow SET candidate_message_id=NULL,revision=revision+1
                WHERE task_id=?''', (task_id,))
            await db.execute('''INSERT INTO task_workflow_events
                (id,task_id,action,from_phase,to_phase,from_status,to_status,revision,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)''', (new_id(), task_id, 'invariants_changed',
                state['phase'], state['phase'], state['status'], state['status'], state['revision'] + 1, now()))
            await db.commit()
            return await self._read(db, task_id)

    async def record(self, agent_id, conversation_id, check):
        async with self.store.connection() as db:
            task_id = await self._task(db, agent_id, conversation_id)
            await db.execute('''INSERT INTO task_invariant_checks(id,task_id,created_at,payload)
                VALUES(?,?,?,?)''', (check.id, task_id, check.created_at, check.model_dump_json()))
            await db.commit()

    async def check_current(self, agent_id, conversation_id, revision):
        async with self.store.connection() as db:
            task_id = await self._task(db, agent_id, conversation_id)
            row = await (await db.execute('SELECT revision FROM task_invariant_sets WHERE task_id=?',
                (task_id,))).fetchone()
            if (row['revision'] if row else 1) != revision:
                raise AgentError('state_conflict', 'Правила изменились во время ответа. Повторите запрос', 409)
