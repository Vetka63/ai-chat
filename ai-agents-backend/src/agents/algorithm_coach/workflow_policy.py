"""Базовый граф Дня 13; approvals и семантические ограничения появятся позже."""
from typing import Literal
from pydantic import Field, ValidationError, model_validator
from agent_core.models import AgentError, new_id
from capabilities.task_workflow.models import WorkflowModel, Text


class PlanStep(WorkflowModel):
    """Шаг плана со стабильным ID; новые ID назначает сервер."""
    id: str | None = Field(default=None, min_length=1, max_length=64)
    title: Text


class Plan(WorkflowModel):
    """План решения с небольшим упорядоченным списком шагов."""
    steps: list[PlanStep] = Field(min_length=1, max_length=30)

    @model_validator(mode='after')
    def unique_ids(self):
        ids = [s.id for s in self.steps if s.id]
        if len(ids) != len(set(ids)):
            raise ValueError('Повтор ID шага')
        return self


class Solution(WorkflowModel):
    """Текст решения, не исполняемый приложением."""
    text: Text


class ValidationReport(WorkflowModel):
    """Источник проверки обязателен: review модели не является запуском тестов."""
    text: Text
    method: Literal['llm_review', 'user_test_result']


class CoachWorkflowPolicy:
    """Переходы разрешает сервер, фаза не определяется по словам модели."""
    graph = {
        'planning': {'start_execution': 'execution'},
        'execution': {'start_validation': 'validation', 'request_replan': 'planning'},
        'validation': {'finish': 'done', 'request_changes': 'execution', 'request_replan': 'planning'},
        'done': {},
    }

    def allowed(self, state):
        if state.status == 'paused':
            return ['resume']
        return list(self.graph[state.phase]) + (['pause'] if state.phase != 'done' else [])

    def require_active(self, state):
        if state.status == 'paused':
            raise AgentError('task_paused', 'Задача на паузе. Нажмите «Продолжить»', 409)
        if state.phase == 'done':
            raise AgentError('task_done', 'Задача завершена. Для нового решения создайте новую задачу', 409)

    def transition(self, state, event, artifacts):
        if event not in self.allowed(state):
            raise AgentError('transition_not_allowed', 'Этот переход недопустим на текущем этапе', 409)
        if event == 'pause':
            state.status = 'paused'
        elif event == 'resume':
            state.status = 'active'
        else:
            state.phase = self.graph[state.phase][event]
        self.expected(state, artifacts)

    def expected(self, state, artifacts):
        # При паузе сохраняем конкретное ожидаемое действие для продолжения.
        latest = {a.kind: a for a in artifacts}
        if state.phase == 'planning':
            state.expected_action = 'start_execution' if 'plan' in latest else 'save_plan'
        elif state.phase == 'execution':
            solution = latest.get('solution')
            plan_revision = latest['plan'].revision if 'plan' in latest else None
            state.expected_action = 'start_validation' if solution and solution.plan_revision == plan_revision else 'work_on_step' if state.current_step_id else 'save_solution'
        elif state.phase == 'validation':
            report = latest.get('validation')
            solution_revision = latest['solution'].revision if 'solution' in latest else None
            state.expected_action = 'finish' if report and report.solution_revision == solution_revision else 'review_solution'
        else:
            state.expected_action = 'completed'

    def artifact(self, kind, content, state, artifacts):
        self.require_active(state)
        if {'plan': 'planning', 'solution': 'execution', 'validation': 'validation'}[kind] != state.phase:
            raise AgentError('artifact_not_allowed', 'Сохраните этот артефакт на соответствующем этапе', 409)
        try:
            parsed = {'plan': Plan, 'solution': Solution, 'validation': ValidationReport}[kind].model_validate(content)
        except ValidationError as exc:
            raise AgentError('invalid_artifact', 'Проверьте поля артефакта: непустой текст, шаги или источник проверки') from exc
        if kind == 'plan':
            old = next((a for a in reversed(artifacts) if a.kind == 'plan'), None)
            old_ids = {s['id'] for s in old.content['steps']} if old else set()
            for step in parsed.steps:
                if step.id and step.id not in old_ids:
                    raise AgentError('invalid_step', 'ID шага должен принадлежать предыдущему плану')
                step.id = step.id or new_id()
            state.current_step_id = parsed.steps[0].id
        return parsed.model_dump()

    def select_step(self, state, step_id, artifacts):
        self.require_active(state)
        plan = next((a for a in reversed(artifacts) if a.kind == 'plan'), None)
        if not plan or step_id not in {s['id'] for s in plan.content['steps']}:
            raise AgentError('invalid_step', 'Выберите шаг текущей версии плана')
        state.current_step_id = step_id
