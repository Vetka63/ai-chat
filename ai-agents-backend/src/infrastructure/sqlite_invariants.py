"""Локальное хранение правил. Сетевые запросы в этом модуле отсутствуют."""
from agent_core.models import AgentError, new_id
from capabilities.invariants.models import TaskInvariant, InvariantCheck, InvariantWorkspace


class SqliteInvariantRepository:
    """Правила и возврат к планированию фиксируются одной SQLite-транзакцией."""
    def __init__(self, memory, workflow):
        self.memory, self.workflow, self.store = memory, workflow, memory.store

    async def read(self, db, task):
        rules = await (await db.execute('SELECT payload FROM task_invariants WHERE task_id=? ORDER BY rowid', (task['id'],))).fetchall()
        checks = await (await db.execute('SELECT payload FROM invariant_checks WHERE task_id=? ORDER BY rowid DESC LIMIT 50', (task['id'],))).fetchall()
        return InvariantWorkspace(revision=task['invariant_revision'],
            rules=[TaskInvariant.model_validate_json(r['payload']) for r in rules],
            checks=[InvariantCheck.model_validate_json(r['payload']) for r in reversed(checks)])

    async def save(self, agent_id, conversation_id, command, policy):
        policy.validate_rules(command.rules)
        async with self.store.connection() as db:
            await db.execute('BEGIN IMMEDIATE')
            task = await self.memory._task(db, agent_id, conversation_id)
            fingerprint = self.workflow.fingerprint('invariants', command)
            old_result = await self.workflow.replay(db, task['id'], command.command_id, fingerprint)
            if old_result is not None:
                return InvariantWorkspace.model_validate(old_result)
            flow = await self.workflow.read(db, task)
            self.workflow.check_revision(flow, command.expected_revision)
            if flow.state.phase == 'done':
                raise AgentError('task_done', 'Для пересмотра завершённой задачи создайте новую', 409)
            before = flow.state.model_dump()
            previous = {r.id: r for r in (await self.read(db, task)).rules}
            for draft in command.rules:
                if draft.id and draft.id not in previous:
                    raise AgentError('invariant_not_found', 'Правило не принадлежит этой задаче', 404)
            await db.execute('DELETE FROM task_invariants WHERE task_id=?', (task['id'],))
            for draft in command.rules:
                rule = TaskInvariant(**{**draft.model_dump(), 'id': draft.id or new_id()},
                    revision=previous[draft.id].revision+1 if draft.id else 1)
                await db.execute('INSERT INTO task_invariants VALUES(?,?,?)', (rule.id, task['id'], rule.model_dump_json()))
            await db.execute('UPDATE tasks SET invariant_revision=invariant_revision+1 WHERE id=?', (task['id'],))
            self.workflow.policy.invalidate(flow.state, flow.artifacts)
            await self.workflow.event(db, flow, 'invariants_changed', before, command.command_id)
            fresh = await self.memory._task(db, agent_id, conversation_id)
            result = await self.read(db, fresh)
            await db.execute('INSERT INTO task_commands VALUES(?,?,?,?,?,?)',
                (task['id'], command.command_id, fingerprint, 'success', result.model_dump_json(), None))
            await db.commit()
            return result

    async def record(self, workspace, check):
        """Локальный аудит использованного снимка, без изменения фазы задачи."""
        async with self.store.connection() as db:
            await self.memory._task(db, workspace.task.agent_id, workspace.task.conversation_id)
            await db.execute('INSERT INTO invariant_checks VALUES(?,?,?,?)',
                (check.id, workspace.task.id, check.created_at, check.model_dump_json()))
            await db.commit()

    async def check_current(self, workspace):
        async with self.store.connection() as db:
            await db.execute('BEGIN')
            task = await self.memory._task(db, workspace.task.agent_id, workspace.task.conversation_id, self.memory.versions(workspace))
            flow = await self.workflow.read(db, task)
            self.workflow.check_revision(flow, workspace.workflow.state.revision)
            self.workflow.policy.require_active(flow.state)
