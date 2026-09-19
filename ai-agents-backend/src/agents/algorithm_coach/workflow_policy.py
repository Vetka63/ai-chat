"""Граф Дня 15: подтверждения конкретных версий и условия переходов."""
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
    blocking_issues: list[Text] = Field(max_length=30)


class CoachWorkflowPolicy:
    """Переходы разрешает сервер, фаза не определяется по словам модели."""
    graph = {
        'planning': {'approve_plan': 'execution'},
        'execution': {'submit_solution': 'validation', 'request_replan': 'planning'},
        'validation': {'accept_validation': 'done', 'request_changes': 'execution', 'request_replan': 'planning'},
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
        if state.phase in ('execution', 'validation') and not state.approved_plan_id:
            raise AgentError('approval_required', 'У старой задачи нет подтверждения плана. Выберите «Перепланировать», сохраните и утвердите новый план', 409)

    def current(self, state, artifacts):
        """Выбирает только результаты текущего цикла, плана и решения."""
        latest = {a.kind: a for a in artifacts if a.checked_workflow_version >= 15}
        plan = latest.get('plan')
        if plan and plan.revision <= state.plan_floor_revision:
            latest.pop('plan')
        solution = latest.get('solution')
        if solution and (solution.revision <= state.solution_floor_revision or not self.approved(state, latest) or solution.plan_revision != state.approved_plan_revision):
            latest.pop('solution')
        report = latest.get('validation')
        if report and (not latest.get('solution') or report.solution_revision != latest['solution'].revision or report.plan_revision != state.approved_plan_revision):
            latest.pop('validation')
        return latest

    def approved(self, state, latest):
        plan = latest.get('plan')
        return bool(plan and plan.id == state.approved_plan_id
            and plan.revision == state.approved_plan_revision
            and plan.task_revision == state.approved_task_revision
            and plan.invariant_revision == state.approved_invariant_revision)

    def reason(self, state, event, artifacts):
        latest = self.current(state, artifacts)
        if event == 'approve_plan' and not latest.get('plan'):
            return 'Сохраните новый актуальный план перед утверждением.'
        if event in ('submit_solution', 'accept_validation') and not self.approved(state, latest):
            return 'Нет утверждения актуального плана. Вернитесь к планированию.'
        if event == 'submit_solution' and not latest.get('solution'):
            return 'Сохраните новую проверенную версию решения для утверждённого плана.'
        if event == 'accept_validation':
            report, solution = latest.get('validation'), latest.get('solution')
            if not solution or solution.id != state.submitted_solution_id:
                return 'Текущая версия решения не передана на проверку.'
            if not report:
                return 'Сохраните отчёт для текущей версии решения.'
            if 'blocking_issues' not in report.content or report.content['blocking_issues']:
                return 'Отчёт содержит блокирующие замечания или не содержит их явного списка.'
        return None

    def availability(self, state, artifacts):
        blocked = {e: reason for e in self.allowed(state) if (reason := self.reason(state, e, artifacts))}
        return [e for e in self.allowed(state) if e not in blocked], blocked

    def clear_approval(self, state):
        state.approved_plan_id = state.approved_plan_revision = None
        state.approved_task_revision = state.approved_invariant_revision = None
        state.submitted_solution_id = state.submitted_solution_revision = None
        state.accepted_validation_id = state.validated_solution_revision = None

    def invalidate(self, state, artifacts):
        """Требует нового плана; прежние результаты и пауза сохраняются."""
        state.plan_floor_revision = max([state.plan_floor_revision] + [a.revision for a in artifacts if a.kind == 'plan'])
        state.phase, state.current_step_id, state.expected_action = 'planning', None, 'save_plan'
        state.change_request = None
        self.clear_approval(state)

    def transition(self, state, event, artifacts, command=None):
        if event not in self.allowed(state):
            raise AgentError('transition_not_allowed', 'Этот переход недопустим. Используйте явное утверждение актуальной версии в панели задачи', 409)
        reason = self.reason(state, event, artifacts)
        if reason:
            raise AgentError('transition_blocked', reason, 409)
        latest = self.current(state, artifacts)
        kind = {'approve_plan': 'plan', 'submit_solution': 'solution', 'accept_validation': 'validation'}.get(event)
        if kind and (command is None or command.artifact_id != latest[kind].id):
            raise AgentError('artifact_conflict', 'Подтвердите точный ID актуальной версии артефакта; обновите панель', 409)
        if event == 'pause': state.status = 'paused'
        elif event == 'resume': state.status = 'active'
        elif event == 'request_replan': self.invalidate(state, artifacts)
        elif event == 'approve_plan':
            plan = latest['plan']
            state.approved_plan_id, state.approved_plan_revision = plan.id, plan.revision
            state.approved_task_revision, state.approved_invariant_revision = plan.task_revision, plan.invariant_revision
            state.phase = 'execution'
        elif event == 'submit_solution':
            state.submitted_solution_id, state.submitted_solution_revision = latest['solution'].id, latest['solution'].revision
            state.phase = 'validation'
        elif event == 'accept_validation':
            state.accepted_validation_id = latest['validation'].id
            state.validated_solution_revision = latest['solution'].revision
            state.phase = 'done'
        elif event == 'request_changes':
            plan = latest.get('plan')
            if command is None or not command.remarks or not plan or command.step_id not in {s['id'] for s in plan.content['steps']}:
                raise AgentError('changes_required', 'Укажите замечания и шаг текущего плана для исправления', 409)
            state.change_request, state.current_step_id = command.remarks, command.step_id
            state.solution_floor_revision = max([state.solution_floor_revision] + [a.revision for a in artifacts if a.kind == 'solution'])
            state.submitted_solution_id = state.submitted_solution_revision = None
            state.accepted_validation_id = state.validated_solution_revision = None
            state.phase = 'execution'
        self.expected(state, artifacts)

    def expected(self, state, artifacts):
        latest = self.current(state, artifacts)
        if state.phase == 'planning': state.expected_action = 'approve_plan' if latest.get('plan') else 'save_plan'
        elif state.phase == 'execution': state.expected_action = 'submit_solution' if latest.get('solution') else 'work_on_step' if state.current_step_id else 'save_solution'
        elif state.phase == 'validation': state.expected_action = 'accept_validation' if not self.reason(state, 'accept_validation', artifacts) else 'review_solution'
        else: state.expected_action = 'completed'

    def artifact(self, kind, content, state, artifacts):
        self.require_active(state)
        if {'plan': 'planning', 'solution': 'execution', 'validation': 'validation'}[kind] != state.phase:
            raise AgentError('artifact_not_allowed', 'Сохраните этот артефакт на соответствующем этапе', 409)
        if kind != 'plan' and not self.approved(state, self.current(state, artifacts)):
            raise AgentError('transition_blocked', 'Сначала утвердите актуальный план', 409)
        try:
            parsed = {'plan': Plan, 'solution': Solution, 'validation': ValidationReport}[kind].model_validate(content)
        except ValidationError as exc:
            raise AgentError('invalid_artifact', 'Проверьте поля: текст, шаги, источник и явный список блокирующих замечаний') from exc
        if kind == 'plan':
            old = next((a for a in reversed(artifacts) if a.kind == 'plan'), None)
            old_ids = {s['id'] for s in old.content['steps']} if old else set()
            for step in parsed.steps:
                if step.id and step.id not in old_ids:
                    raise AgentError('invalid_step', 'ID шага должен принадлежать предыдущему плану')
                step.id = step.id or new_id()
            self.clear_approval(state)
            state.current_step_id = parsed.steps[0].id
        if kind == 'solution':
            state.submitted_solution_id = state.submitted_solution_revision = None
            state.accepted_validation_id = state.validated_solution_revision = None
        return parsed.model_dump()

    def select_step(self, state, step_id, artifacts):
        self.require_active(state)
        plan = self.current(state, artifacts).get('plan')
        if not plan or step_id not in {s['id'] for s in plan.content['steps']}:
            raise AgentError('invalid_step', 'Выберите шаг текущей версии плана')
        state.current_step_id = step_id
