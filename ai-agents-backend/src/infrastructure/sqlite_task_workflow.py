"""Атомарное хранение автомата, артефактов, событий и идемпотентных команд."""
import hashlib
import json
from agent_core.models import AgentError, AgentResult, new_id, now
from capabilities.task_workflow.models import TaskState, TaskArtifact, WorkflowWorkspace


class SqliteTaskUnitOfWork:
    """Одна короткая SQLite-транзакция на изменение; LLM вызывается вне неё."""
    def __init__(self, store, memory, policy):
        self.store, self.memory, self.policy = store, memory, policy

    async def initialize(self):
        """Один процесс приложения: незавершённые команды не повторяются после рестарта."""
        async with self.store.connection() as db:
            await db.execute("UPDATE task_commands SET status='interrupted' WHERE status='pending'")
            await db.commit()

    async def read(self, db, task):
        row = await (await db.execute('SELECT payload FROM task_states WHERE task_id=?', (task['id'],))).fetchone()
        state = TaskState.model_validate_json(row['payload'])
        artifacts = await (await db.execute('SELECT payload FROM task_artifacts WHERE task_id=? ORDER BY rowid', (task['id'],))).fetchall()
        events = await (await db.execute('SELECT payload FROM task_events WHERE task_id=? ORDER BY sequence', (task['id'],))).fetchall()
        active = await (await db.execute("SELECT command_id FROM task_commands WHERE task_id=? AND status='pending'", (task['id'],))).fetchone()
        return WorkflowWorkspace(task_id=task['id'], state=state,
            artifacts=[TaskArtifact.model_validate_json(a['payload']) for a in artifacts],
            events=[json.loads(e['payload']) for e in events], allowed_events=self.policy.allowed(state),
            active_command_id=active['command_id'] if active else None)

    async def workspace(self, agent_id, conversation_id):
        async with self.store.connection() as db:
            await db.execute('BEGIN')
            task = await self.memory._task(db, agent_id, conversation_id)
            return await self.read(db, task)

    @staticmethod
    def fingerprint(operation, command):
        data = command.model_dump(mode='json')
        return hashlib.sha256(json.dumps([operation, data], sort_keys=True, ensure_ascii=False).encode()).hexdigest()

    async def replay(self, db, task_id, command_id, fingerprint):
        row = await (await db.execute('SELECT * FROM task_commands WHERE task_id=? AND command_id=?', (task_id, command_id))).fetchone()
        if row is None:
            return None
        if row['fingerprint'] != fingerprint:
            raise AgentError('command_conflict', 'Этот ID команды уже использован с другим содержимым', 409)
        if row['status'] == 'success':
            return json.loads(row['result'])
        raise AgentError('command_'+row['status'], 'Команда уже выполнялась: '+row['status']+'. Обновите состояние. Новый вызов требует нового ID', 409)

    @staticmethod
    def check_revision(workspace, expected):
        if expected is not None and workspace.state.revision != expected:
            raise AgentError('state_conflict', 'Этап или шаг изменились. Обновите панель задачи', 409)

    async def event(self, db, workspace, name, before, command_id):
        state = workspace.state
        state.revision += 1
        await db.execute('UPDATE task_states SET payload=? WHERE task_id=?', (state.model_dump_json(), workspace.task_id))
        payload = {'event': name, 'command_id': command_id, 'before': before,
                   'after': state.model_dump(), 'created_at': now()}
        await db.execute('INSERT INTO task_events(task_id,payload) VALUES(?,?)', (workspace.task_id, json.dumps(payload, ensure_ascii=False)))

    async def change(self, agent_id, conversation_id, operation, command, policy):
        async with self.store.connection() as db:
            await db.execute('BEGIN IMMEDIATE')
            task = await self.memory._task(db, agent_id, conversation_id)
            fingerprint = self.fingerprint(operation, command)
            previous = await self.replay(db, task['id'], command.command_id, fingerprint)
            if previous is not None:
                return WorkflowWorkspace.model_validate(previous)
            workspace = await self.read(db, task)
            self.check_revision(workspace, command.expected_revision)
            # Пауза не ждёт ответа LLM. Resume тоже допустим: версия всё равно отвергнет поздний ответ.
            if workspace.active_command_id and not (operation == 'transition' and command.event in ('pause', 'resume')):
                raise AgentError('task_busy', 'Дождитесь текущего вызова; поставить на паузу можно сейчас', 409)
            before = workspace.state.model_dump()
            name = operation
            if operation == 'transition':
                name = command.event
                policy.transition(workspace.state, command.event, workspace.artifacts)
            elif operation == 'step':
                policy.select_step(workspace.state, command.step_id, workspace.artifacts)
            elif operation == 'artifact':
                content = policy.artifact(command.kind, command.content, workspace.state, workspace.artifacts)
                if command.source_message_id is not None:
                    await self.memory._source(db, conversation_id, command.source_message_id)
                latest = {a.kind: a for a in workspace.artifacts}
                artifact = TaskArtifact(id=new_id(), kind=command.kind,
                    revision=latest[command.kind].revision+1 if command.kind in latest else 1,
                    content=content, source_message_id=command.source_message_id, task_revision=task['revision'],
                    plan_revision=latest['plan'].revision if 'plan' in latest else None,
                    solution_revision=latest['solution'].revision if 'solution' in latest else None, created_at=now())
                await db.execute('INSERT INTO task_artifacts VALUES(?,?,?,?,?)',
                    (artifact.id, task['id'], artifact.kind, artifact.revision, artifact.model_dump_json()))
                workspace.artifacts.append(artifact)
                name = 'save_'+command.kind
            policy.expected(workspace.state, workspace.artifacts)
            await self.event(db, workspace, name, before, command.command_id)
            result = await self.read(db, task)
            await db.execute('INSERT INTO task_commands VALUES(?,?,?,?,?,?)',
                (task['id'], command.command_id, fingerprint, 'success', result.model_dump_json(), None))
            await db.commit()
            return result

    async def reserve(self, workspace, command, spec_id, limit):
        """Вопрос и reservation записываются вместе; повтор не вызывает модель второй раз."""
        async with self.store.connection() as db:
            await db.execute('BEGIN IMMEDIATE')
            task = await self.memory._task(db, workspace.task.agent_id, workspace.task.conversation_id)
            fingerprint = self.fingerprint('run', command)
            previous = await self.replay(db, task['id'], command.command_id, fingerprint)
            if previous is not None:
                return AgentResult.model_validate(previous)
            await self.memory._task(db, workspace.task.agent_id, workspace.task.conversation_id, self.memory.versions(workspace))
            flow = await self.read(db, task)
            self.check_revision(flow, command.expected_revision)
            self.check_revision(flow, workspace.workflow.state.revision)
            self.policy.require_active(flow.state)
            if flow.active_command_id:
                raise AgentError('task_busy', 'Для этой задачи уже выполняется запрос', 409)
            run_id = new_id()
            await db.execute('INSERT INTO task_commands VALUES(?,?,?,?,?,?)',
                (task['id'], command.command_id, fingerprint, 'pending', None, run_id))
            await db.execute('INSERT INTO messages(conversation_id,role,content,created_at) VALUES(?,?,?,?)',
                (task['conversation_id'], 'user', command.message.strip(), now()))
            await db.execute('UPDATE conversations SET selected_model_id=?,max_output_tokens=?,updated_at=? WHERE id=?',
                (spec_id, limit, now(), task['conversation_id']))
            await db.commit()
            return run_id

    async def complete(self, workspace, command, result):
        """Ответ, финальный run, событие и replay-кэш применяются атомарно после проверки версий."""
        async with self.store.connection() as db:
            await db.execute('BEGIN IMMEDIATE')
            task = await self.memory._task(db, workspace.task.agent_id, workspace.task.conversation_id, self.memory.versions(workspace))
            flow = await self.read(db, task)
            self.check_revision(flow, workspace.workflow.state.revision)
            self.policy.require_active(flow.state)
            if flow.active_command_id != command.command_id:
                raise AgentError('state_conflict', 'Запуск больше не является активным', 409)
            await db.execute('INSERT INTO messages(conversation_id,role,content,created_at) VALUES(?,?,?,?)',
                (task['conversation_id'], 'assistant', result.reply, now()))
            await db.execute('UPDATE conversations SET updated_at=? WHERE id=?', (now(), task['conversation_id']))
            await self.event(db, flow, 'reply_saved', flow.state.model_dump(), command.command_id)
            run = result.run
            await db.execute('INSERT INTO runs VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload',
                (run.id, run.conversation_id, run.agent_id, run.created_at, run.model_dump_json()))
            await db.execute("UPDATE task_commands SET status='success',result=? WHERE task_id=? AND command_id=?",
                (result.model_dump_json(), task['id'], command.command_id))
            await db.commit()

    async def fail(self, task_id, command_id, status='error'):
        """Освобождает reservation без повторного платного вызова и без продвижения этапа."""
        async with self.store.connection() as db:
            await db.execute("UPDATE task_commands SET status=? WHERE task_id=? AND command_id=? AND status='pending'",
                             (status, task_id, command_id))
            await db.commit()
