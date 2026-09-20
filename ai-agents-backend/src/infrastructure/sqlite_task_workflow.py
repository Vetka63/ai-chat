"""Атомарное хранение автомата и версий результатов одной задачи."""
import json

from agent_core.models import AgentError, new_id, now
from capabilities.task_workflow.models import WorkflowArtifact, WorkflowEvent, WorkflowState, WorkflowWorkspace
from infrastructure.migrations import migrate_memory_layers


def expected_action(phase, status, candidate_message_id):
    """Следующее действие определяется сохранённым этапом и наличием ответа."""
    if status == 'paused':
        return 'resume'
    if phase == 'done':
        return 'completed'
    if candidate_message_id is None:
        return {'planning': 'discuss_plan', 'execution': 'work_on_step',
                'validation': 'review_solution'}[phase]
    return {'planning': 'accept_plan', 'execution': 'accept_solution',
            'validation': 'accept_validation'}[phase]


class SqliteWorkflowRepository:
    """Применяет действия к текущей версии задачи в одной SQLite-транзакции."""

    def __init__(self, store):
        self.store = store

    async def initialize(self):
        await migrate_memory_layers(self.store)

    async def _task(self, db, agent_id, conversation_id):
        row = await (await db.execute('''SELECT t.id FROM tasks t JOIN conversations c ON c.id=t.conversation_id
            WHERE t.agent_id=? AND c.agent_id=? AND t.conversation_id=?''',
            (agent_id, agent_id, conversation_id))).fetchone()
        if row is None:
            raise AgentError('task_not_found', 'Задача не найдена', 404)
        return row['id']

    async def _workspace(self, db, task_id):
        row = await (await db.execute('SELECT * FROM task_workflow WHERE task_id=?', (task_id,))).fetchone()
        if row is None:
            raise AgentError('workflow_not_found', 'Состояние задачи не найдено', 404)
        artifact_rows = await (await db.execute('''SELECT id,kind,revision,content,source_message_id,created_at
            FROM task_artifacts WHERE task_id=? ORDER BY created_at,id''', (task_id,))).fetchall()
        event_rows = await (await db.execute('''SELECT action,from_phase,to_phase,from_status,to_status,revision,created_at
            FROM task_workflow_events WHERE task_id=? ORDER BY revision''', (task_id,))).fetchall()
        candidate = None
        if row['candidate_message_id'] is not None:
            candidate = await (await db.execute('SELECT content FROM messages WHERE sequence=? AND role=?',
                (row['candidate_message_id'], 'assistant'))).fetchone()
        return WorkflowWorkspace(task_id=task_id,
            state=WorkflowState(phase=row['phase'], status=row['status'], current_step_id=row['current_step_id'],
                candidate_message_id=row['candidate_message_id'],
                expected_action=expected_action(row['phase'], row['status'], row['candidate_message_id']),
                revision=row['revision']),
            candidate_text=candidate['content'] if candidate else None,
            artifacts=[WorkflowArtifact(**{**dict(item), 'content': json.loads(item['content'])}) for item in artifact_rows],
            events=[WorkflowEvent.model_validate(dict(item)) for item in event_rows])

    async def workspace(self, agent_id, conversation_id):
        async with self.store.connection() as db:
            await db.execute('BEGIN')
            task_id = await self._task(db, agent_id, conversation_id)
            return await self._workspace(db, task_id)

    async def clear_candidate(self, agent_id, conversation_id, expected_revision):
        """Новый вопрос делает прежнее предложение неактуальным."""
        async with self.store.connection() as db:
            await db.execute('BEGIN IMMEDIATE')
            task_id = await self._task(db, agent_id, conversation_id)
            row = await (await db.execute('SELECT revision,status,phase FROM task_workflow WHERE task_id=?',
                (task_id,))).fetchone()
            if row['revision'] != expected_revision or row['status'] != 'active' or row['phase'] == 'done':
                raise AgentError('state_conflict', 'Состояние задачи изменилось. Обновите чат', 409)
            await db.execute('UPDATE task_workflow SET candidate_message_id=NULL WHERE task_id=?', (task_id,))
            await db.commit()

    async def apply(self, agent_id, conversation_id, command):
        async with self.store.connection() as db:
            await db.execute('BEGIN IMMEDIATE')
            task_id = await self._task(db, agent_id, conversation_id)
            workspace = await self._workspace(db, task_id)
            old = workspace.state
            if command.expected_revision != old.revision:
                raise AgentError('state_conflict', 'Состояние изменилось. Обновите задачу и повторите действие', 409)
            action = command.action
            phase, status, step_id = old.phase, old.status, old.current_step_id
            if action == 'pause':
                if status != 'active' or phase == 'done':
                    raise AgentError('invalid_transition', 'Сейчас нельзя поставить задачу на паузу', 409)
                status = 'paused'
            elif action == 'resume':
                if status != 'paused':
                    raise AgentError('invalid_transition', 'Задача не находится на паузе', 409)
                status = 'active'
            else:
                if status == 'paused' or phase == 'done':
                    raise AgentError('invalid_transition', 'Сначала продолжите задачу', 409)
                if action == 'select_step':
                    plan = next((a for a in reversed(workspace.artifacts) if a.kind == 'plan'), None)
                    if phase != 'execution' or plan is None or command.step_id not in [s['id'] for s in plan.content['steps']]:
                        raise AgentError('invalid_step', 'Выберите шаг сохранённого плана во время реализации', 409)
                    step_id = command.step_id
                else:
                    transitions = {'accept_plan': ('planning', 'execution', 'plan'),
                                   'accept_solution': ('execution', 'validation', 'solution'),
                                   'accept_validation': ('validation', 'done', 'validation')}
                    required, target, kind = transitions[action]
                    if phase != required:
                        raise AgentError('invalid_transition', f'Действие доступно только на этапе {required}', 409)
                    if old.candidate_message_id is None:
                        raise AgentError('missing_candidate', 'Сначала получите ответ агента на текущем этапе', 409)
                    if not command.content:
                        raise AgentError('invalid_artifact', 'Результат для сохранения пуст', 422)
                    if kind == 'plan':
                        steps = command.content.get('steps')
                        if not isinstance(steps, list) or not 1 <= len(steps) <= 30 or not all(
                            isinstance(s, str) and s.strip() and len(s) <= 500 for s in steps):
                            raise AgentError('invalid_plan', 'План должен содержать от 1 до 30 непустых шагов', 422)
                        content = {'steps': [{'id': new_id(), 'title': s.strip()} for s in steps]}
                        step_id = content['steps'][0]['id']
                    else:
                        value = command.content.get('text')
                        if not isinstance(value, str) or not value.strip() or len(value) > 30000:
                            raise AgentError('invalid_artifact', 'Нужен непустой текст результата', 422)
                        content = {'text': value.strip()}
                        if kind == 'validation':
                            method = command.content.get('method', 'llm_review')
                            if method not in ('llm_review', 'user_test_result'):
                                raise AgentError('invalid_artifact', 'Выберите источник проверки', 422)
                            content['method'] = method
                    version = 1 + max((a.revision for a in workspace.artifacts if a.kind == kind), default=0)
                    await db.execute('''INSERT INTO task_artifacts
                        (id,task_id,kind,revision,content,source_message_id,created_at) VALUES(?,?,?,?,?,?,?)''',
                        (new_id(), task_id, kind, version, json.dumps(content, ensure_ascii=False),
                         old.candidate_message_id, now()))
                    phase = target
                    if phase == 'done':
                        step_id = None
            revision = old.revision + 1
            candidate_id = old.candidate_message_id if action in ('pause', 'resume') else None
            await db.execute('''UPDATE task_workflow SET phase=?,status=?,current_step_id=?,candidate_message_id=?,revision=? WHERE task_id=?''',
                (phase, status, step_id, candidate_id, revision, task_id))
            await db.execute('''INSERT INTO task_workflow_events
                (id,task_id,action,from_phase,to_phase,from_status,to_status,revision,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)''',
                (new_id(), task_id, action, old.phase, phase, old.status, status, revision, now()))
            await db.commit()
            return await self._workspace(db, task_id)
