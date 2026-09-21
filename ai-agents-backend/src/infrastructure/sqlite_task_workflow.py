"""Атомарное хранение автомата, версий артефактов и разрешений переходов."""
import json

from agent_core.models import AgentError, new_id, now
from capabilities.task_workflow.models import (
    ArtifactReference, WorkflowArtifact, WorkflowControl, WorkflowEvent,
    WorkflowState, WorkflowWorkspace,
)
from capabilities.task_workflow.policy import WorkflowTransitionPolicy
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
    """Проверяет и применяет каждую команду в одной SQLite-транзакции."""

    def __init__(self, store):
        self.store = store
        self.policy = WorkflowTransitionPolicy()

    async def initialize(self):
        await migrate_memory_layers(self.store)

    async def _task(self, db, agent_id, conversation_id):
        row = await (await db.execute('''SELECT t.id,t.conversation_id,t.revision,
                COALESCE(s.revision,1) AS invariant_revision
            FROM tasks t JOIN conversations c ON c.id=t.conversation_id
            LEFT JOIN task_invariant_sets s ON s.task_id=t.id
            WHERE t.agent_id=? AND c.agent_id=? AND t.conversation_id=?''',
            (agent_id, agent_id, conversation_id))).fetchone()
        if row is None:
            raise AgentError('task_not_found', 'Задача не найдена', 404)
        return row

    @staticmethod
    async def _control(db, task_id, artifacts):
        row = await (await db.execute('SELECT * FROM task_lifecycle_control WHERE task_id=?',
            (task_id,))).fetchone()
        values = dict(row) if row else {}
        by_id = {item.id: item for item in artifacts}

        def reference(name):
            item = by_id.get(values.get(name))
            return ArtifactReference(id=item.id, revision=item.revision) if item else None

        return WorkflowControl(approved_plan=reference('approved_plan_id'),
            current_solution=reference('current_solution_id'),
            current_validation=reference('current_validation_id'),
            approved_task_revision=values.get('approved_task_revision'),
            approved_invariant_revision=values.get('approved_invariant_revision'),
            validation_solution_id=values.get('validation_solution_id'),
            change_request=values.get('change_request'))

    async def _workspace(self, db, task):
        task_id = task['id']
        row = await (await db.execute('SELECT * FROM task_workflow WHERE task_id=?', (task_id,))).fetchone()
        if row is None:
            raise AgentError('workflow_not_found', 'Состояние задачи не найдено', 404)
        artifact_rows = await (await db.execute('''SELECT id,kind,revision,content,based_on_artifact_id,
                task_revision,invariant_revision,source_message_id,created_at
            FROM task_artifacts WHERE task_id=? ORDER BY created_at,id''', (task_id,))).fetchall()
        event_rows = await (await db.execute('''SELECT action,from_phase,to_phase,from_status,to_status,revision,created_at
            FROM task_workflow_events WHERE task_id=? ORDER BY revision''', (task_id,))).fetchall()
        candidate = None
        if row['candidate_message_id'] is not None:
            candidate = await (await db.execute('''SELECT content FROM messages
                WHERE sequence=? AND conversation_id=? AND role=?''',
                (row['candidate_message_id'], task['conversation_id'], 'assistant'))).fetchone()
        artifacts = [WorkflowArtifact(**{**dict(item), 'content': json.loads(item['content'])})
            for item in artifact_rows]
        control = await self._control(db, task_id, artifacts)
        state = WorkflowState(phase=row['phase'], status=row['status'], current_step_id=row['current_step_id'],
            candidate_message_id=row['candidate_message_id'],
            expected_action=expected_action(row['phase'], row['status'], row['candidate_message_id']),
            revision=row['revision'])
        transitions = self.policy.options(state.phase, state.status, state.candidate_message_id,
            control, task['revision'], task['invariant_revision'])
        return WorkflowWorkspace(task_id=task_id, task_revision=task['revision'],
            invariant_revision=task['invariant_revision'], state=state, control=control,
            transitions=transitions,
            candidate_text=candidate['content'] if candidate else None,
            artifacts=artifacts,
            events=[WorkflowEvent.model_validate(dict(item)) for item in event_rows])

    async def workspace(self, agent_id, conversation_id):
        async with self.store.connection() as db:
            await db.execute('BEGIN')
            task = await self._task(db, agent_id, conversation_id)
            return await self._workspace(db, task)

    async def clear_candidate(self, agent_id, conversation_id, expected_revision):
        """Новый вопрос делает прежнее предложение неактуальным."""
        async with self.store.connection() as db:
            await db.execute('BEGIN IMMEDIATE')
            task = await self._task(db, agent_id, conversation_id)
            row = await (await db.execute('''SELECT revision,status,phase,candidate_message_id
                FROM task_workflow WHERE task_id=?''',
                (task['id'],))).fetchone()
            if row['revision'] != expected_revision or row['status'] != 'active' or row['phase'] == 'done':
                raise AgentError('state_conflict', 'Состояние задачи изменилось. Обновите чат', 409)
            if row['candidate_message_id'] is not None:
                await db.execute('''UPDATE task_workflow SET candidate_message_id=NULL,revision=revision+1
                    WHERE task_id=?''', (task['id'],))
            await db.commit()

    @staticmethod
    def _transition_reason(command):
        content = command.content or {}
        value = content.get('reason', content.get('text', ''))
        if not isinstance(value, str) or not value.strip() or len(value) > 5000:
            raise AgentError('invalid_transition_data', 'Укажите краткую причину действия', 422)
        return value.strip()

    async def apply(self, agent_id, conversation_id, command):
        async with self.store.connection() as db:
            await db.execute('BEGIN IMMEDIATE')
            task = await self._task(db, agent_id, conversation_id)
            workspace = await self._workspace(db, task)
            old = workspace.state
            if command.expected_revision != old.revision:
                raise AgentError('state_conflict', 'Состояние изменилось. Обновите задачу и повторите действие', 409)
            action = command.action
            option = self.policy.require(action, old.phase, old.status, old.candidate_message_id,
                workspace.control, workspace.task_revision, workspace.invariant_revision)
            if not option.allowed:
                raise AgentError('transition_not_allowed', option.reason or 'Переход недоступен', 409)

            phase, status, step_id = old.phase, old.status, old.current_step_id
            candidate_id = old.candidate_message_id if action in ('pause', 'resume') else None
            if action == 'pause':
                status = 'paused'
            elif action == 'resume':
                status = 'active'
            elif action == 'select_step':
                plan = next((item for item in workspace.artifacts
                    if workspace.control.approved_plan and item.id == workspace.control.approved_plan.id), None)
                if plan is None or command.step_id not in [item['id'] for item in plan.content['steps']]:
                    raise AgentError('invalid_step', 'Выберите шаг актуального утверждённого плана', 409)
                step_id = command.step_id
            elif action == 'request_changes':
                reason = self._transition_reason(command)
                phase = 'execution'
                await db.execute('''UPDATE task_lifecycle_control SET current_validation_id=NULL,
                    validation_solution_id=NULL,change_request=?,updated_at=? WHERE task_id=?''',
                    (reason, now(), task['id']))
            elif action == 'request_replan':
                reason = self._transition_reason(command)
                phase, step_id = 'planning', None
                await db.execute('''UPDATE task_lifecycle_control SET approved_plan_id=NULL,
                    current_solution_id=NULL,current_validation_id=NULL,approved_task_revision=NULL,
                    approved_invariant_revision=NULL,validation_solution_id=NULL,change_request=?,updated_at=?
                    WHERE task_id=?''', (reason, now(), task['id']))
            else:
                content = command.content or {}
                if action == 'accept_plan':
                    steps = content.get('steps')
                    if not isinstance(steps, list) or not 1 <= len(steps) <= 30 or not all(
                        isinstance(item, str) and item.strip() and len(item) <= 500 for item in steps):
                        raise AgentError('invalid_plan', 'План должен содержать от 1 до 30 непустых шагов', 422)
                    artifact_content = {'steps': [{'id': new_id(), 'title': item.strip()} for item in steps]}
                    kind, phase, based_on = 'plan', 'execution', None
                else:
                    value = content.get('text')
                    if not isinstance(value, str) or not value.strip() or len(value) > 30000:
                        raise AgentError('invalid_artifact', 'Нужен непустой текст результата', 422)
                    artifact_content = {'text': value.strip()}
                    if action == 'accept_validation':
                        method = content.get('method', 'llm_review')
                        if method not in ('llm_review', 'user_test_result'):
                            raise AgentError('invalid_artifact', 'Выберите источник проверки', 422)
                        artifact_content['method'] = method
                        kind, phase = 'validation', 'done'
                        based_on = workspace.control.current_solution.id
                    else:
                        kind, phase = 'solution', 'validation'
                        based_on = workspace.control.approved_plan.id
                version = 1 + max((item.revision for item in workspace.artifacts if item.kind == kind), default=0)
                artifact_id = new_id()
                await db.execute('''INSERT INTO task_artifacts
                    (id,task_id,kind,revision,content,based_on_artifact_id,task_revision,invariant_revision,
                     source_message_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)''',
                    (artifact_id, task['id'], kind, version, json.dumps(artifact_content, ensure_ascii=False),
                     based_on, task['revision'], task['invariant_revision'], old.candidate_message_id, now()))
                if kind == 'plan':
                    step_id = artifact_content['steps'][0]['id']
                    await db.execute('''UPDATE task_lifecycle_control SET approved_plan_id=?,
                        current_solution_id=NULL,current_validation_id=NULL,approved_task_revision=?,
                        approved_invariant_revision=?,validation_solution_id=NULL,change_request=NULL,updated_at=?
                        WHERE task_id=?''', (artifact_id, task['revision'], task['invariant_revision'], now(), task['id']))
                elif kind == 'solution':
                    await db.execute('''UPDATE task_lifecycle_control SET current_solution_id=?,
                        current_validation_id=NULL,validation_solution_id=NULL,change_request=NULL,updated_at=?
                        WHERE task_id=?''', (artifact_id, now(), task['id']))
                else:
                    await db.execute('''UPDATE task_lifecycle_control SET current_validation_id=?,
                        validation_solution_id=?,change_request=NULL,updated_at=? WHERE task_id=?''',
                        (artifact_id, workspace.control.current_solution.id, now(), task['id']))
                    step_id = None

            revision = old.revision + 1
            await db.execute('''UPDATE task_workflow SET phase=?,status=?,current_step_id=?,candidate_message_id=?,revision=?
                WHERE task_id=?''', (phase, status, step_id, candidate_id, revision, task['id']))
            await db.execute('''INSERT INTO task_workflow_events
                (id,task_id,action,from_phase,to_phase,from_status,to_status,revision,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)''',
                (new_id(), task['id'], action, old.phase, phase, old.status, status, revision, now()))
            await db.commit()
            refreshed = await self._task(db, agent_id, conversation_id)
            return await self._workspace(db, refreshed)

    async def replace_problem(self, agent_id, conversation_id, problem, versions):
        """Изменение условия атомарно возвращает активную задачу к планированию."""
        async with self.store.connection() as db:
            await db.execute('BEGIN IMMEDIATE')
            row = await (await db.execute('''SELECT t.id,t.revision,p.memory_revision,
                    p.revision AS preferences_revision,w.phase,w.status,w.revision AS workflow_revision
                FROM tasks t JOIN conversations c ON c.id=t.conversation_id
                JOIN profiles p ON p.id=t.profile_id JOIN task_workflow w ON w.task_id=t.id
                WHERE t.agent_id=? AND c.agent_id=? AND t.conversation_id=?''',
                (agent_id, agent_id, conversation_id))).fetchone()
            if row is None:
                raise AgentError('task_not_found', 'Задача не найдена', 404)
            if (row['revision'], row['memory_revision'], row['preferences_revision']) != (
                    versions.task_revision, versions.profile_revision, versions.preferences_revision):
                raise AgentError('state_conflict', 'Память или профиль изменились. Обновите панель', 409)
            if row['status'] == 'paused' or row['phase'] == 'done':
                raise AgentError('transition_not_allowed',
                    'Сначала продолжите задачу; завершённую задачу изменить нельзя', 409)
            await db.execute('UPDATE tasks SET problem=?,revision=revision+1 WHERE id=?',
                (json.dumps(problem, ensure_ascii=False), row['id']))
            revision = row['workflow_revision'] + 1
            await db.execute('''UPDATE task_workflow SET phase='planning',current_step_id=NULL,
                candidate_message_id=NULL,revision=? WHERE task_id=?''', (revision, row['id']))
            await db.execute('''UPDATE task_lifecycle_control SET approved_plan_id=NULL,
                current_solution_id=NULL,current_validation_id=NULL,approved_task_revision=NULL,
                approved_invariant_revision=NULL,validation_solution_id=NULL,
                change_request='Условие задачи изменено',updated_at=? WHERE task_id=?''', (now(), row['id']))
            await db.execute('''INSERT INTO task_workflow_events
                (id,task_id,action,from_phase,to_phase,from_status,to_status,revision,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)''', (new_id(), row['id'], 'problem_changed', row['phase'],
                'planning', row['status'], row['status'], revision, now()))
            await db.commit()
