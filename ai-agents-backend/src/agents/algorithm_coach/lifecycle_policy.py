"""Ограничения содержимого диалога текущим этапом задачи."""
import re

from agent_core.models import AgentError


class CoachLifecyclePolicy:
    """Не полагается на просьбу в промпте для допуска к следующему этапу."""

    phase_labels = {
        'planning': 'планирование',
        'execution': 'реализация',
        'validation': 'проверка',
        'done': 'завершено',
    }
    status_requests = [re.compile(pattern, re.IGNORECASE | re.DOTALL) for pattern in (
        r'(?:в|на)\s+как(?:ой|ом)\s+(?:мы\s+)?(?:сейчас\s+)?(?:фаз|этап|шаг)',
        r'как(?:ая|ой|ом)\s+(?:сейчас\s+)?(?:фаз|этап|статус|шаг)',
        r'где\s+(?:мы|задача)\s+(?:сейчас\s+)?(?:находимся|остановились)',
        r'(?:план|решение|проверка)\s+(?:уже\s+)?(?:есть|принят|утвержд|сохран)',
    )]
    control_requests = [re.compile(pattern, re.IGNORECASE | re.DOTALL) for pattern in (
        r'(?:перейд|переход).{0,60}(?:этап|фаз|реализац|проверк|заверш)',
        r'(?:заверш|законч).{0,40}(?:задач|работ)',
        r'(?:подтверд|прими|сохрани).{0,50}(?:план|решение|проверк)',
    )]

    implementation_requests = [re.compile(pattern, re.IGNORECASE | re.DOTALL) for pattern in (
        r'\b(?:напиши|сгенерируй|покажи|дай)\s+(?:мне\s+)?(?:полное\s+|готов(?:ое|ый|ую)\s+)?(?:решение|код|функци\w*|класс\w*)',
        r'\bреализуй\b',
        r'\b(?:реши|выполни)\s+(?:эту\s+)?задач\w*',
        r'\b(?:сразу|без\s+(?:плана|планирования)).{0,60}(?:код|реализ\w*|решение)',
        r'\b(?:игнорируй|забудь|считай).{0,100}(?:план\w*\s+(?:принят|утвержд)|код|реализ\w*)',
        r'\b(?:implement|write|provide|generate)\s+(?:the\s+|a\s+)?(?:code|implementation|function|class|solution)\b',
        r'\b(?:ignore|pretend|assume).{0,100}(?:plan.{0,20}(?:approved|accepted)|code|implementation)',
    )]
    implementation_output = [re.compile(pattern, re.IGNORECASE | re.MULTILINE) for pattern in (
        r'```(?:python|java|javascript|typescript|cpp|c\+\+|csharp|c#|go|rust|kotlin|swift)?\s*\n',
        r'^\s*(?:def|class|function)\s+[A-Za-z_]\w*\s*[(:]',
        r'^\s*(?:public|private|protected)\s+(?:static\s+)?(?:class|\w+[<\[\], >]*\s+\w+\s*\()',
        r'^\s*#include\s*[<"]',
    )]

    @staticmethod
    def _refusal():
        return ('Сейчас задача находится на этапе планирования. Я могу уточнить условие и подготовить '
            'план, но реализация станет доступна только после явного подтверждения актуального плана '
            'в панели задачи.')

    def ensure_dialogue_allowed(self, workspace):
        """Повреждённая или устаревшая цепочка не открывает режим следующего этапа."""
        state, control = workspace.state, workspace.control
        if state.phase == 'execution' and not (control.approved_plan
                and control.approved_task_revision == workspace.task_revision
                and control.approved_invariant_revision == workspace.invariant_revision):
            raise AgentError('lifecycle_not_ready',
                'Реализация заблокирована: нужен актуальный утверждённый план', 409)
        if state.phase == 'validation' and control.current_solution is None:
            raise AgentError('lifecycle_not_ready',
                'Проверка заблокирована: отсутствует актуальное решение', 409)

    def validate_input(self, text, workspace):
        if workspace.state.phase == 'planning' and any(rule.search(text) for rule in self.implementation_requests):
            return self._refusal()
        return None

    def validate_output(self, text, workspace):
        if workspace.state.phase == 'planning' and any(rule.search(text) for rule in self.implementation_output):
            return self._refusal()
        return None

    def status_response(self, text, workspace):
        """Отвечает на вопрос о состоянии из БД, не предлагая LLM угадывать его по истории."""
        if not any(rule.search(text) for rule in self.status_requests):
            return None
        state, control = workspace.state, workspace.control
        approved = f'План v{control.approved_plan.revision} утверждён' if control.approved_plan else 'План не утверждён'
        if control.current_solution and control.change_request and state.phase == 'execution':
            solution = (f'Решение v{control.current_solution.revision} сохранено как предыдущая версия '
                f'и отправлено на доработку: {control.change_request}')
        else:
            solution = f'Решение v{control.current_solution.revision} сохранено' if control.current_solution else 'Решение не сохранено'
        validation = (f'Проверка v{control.current_validation.revision} сохранена'
            if control.current_validation else 'Проверка не сохранена')
        actions = [item.action for item in workspace.transitions if item.allowed]
        step = ''
        if state.current_step_id:
            plan = next((item for item in workspace.artifacts
                if control.approved_plan and item.id == control.approved_plan.id), None)
            current = next((item for item in (plan.content.get('steps', []) if plan else [])
                if item.get('id') == state.current_step_id), None)
            step = f'\n- Текущий шаг: {current["title"]}' if current else ''
        return (f'Сейчас этап — `{state.phase}` ({self.phase_labels[state.phase]}), статус — `{state.status}`.\n\n'
            f'- {approved}.\n- {solution}.\n- {validation}.{step}\n'
            f'- Доступные действия: {", ".join(actions) if actions else "нет"}.')

    def can_be_candidate(self, request):
        """Служебный разговор о переходах не должен становиться артефактом текущего этапа."""
        return not (any(rule.search(request) for rule in self.status_requests)
            or any(rule.search(request) for rule in self.control_requests))
