"""Единая серверная таблица разрешённых переходов жизненного цикла."""

from .models import TransitionOption


class WorkflowTransitionPolicy:
    """Вычисляет доступность команд; UI не является источником разрешений."""

    visible_actions = ('pause', 'resume', 'accept_plan', 'accept_solution',
                       'accept_validation', 'request_changes', 'request_replan')

    @staticmethod
    def _option(action, phase, status, candidate, control, task_revision, invariant_revision):
        target = {
            'pause': phase, 'resume': phase, 'accept_plan': 'execution',
            'accept_solution': 'validation', 'accept_validation': 'done',
            'request_changes': 'execution', 'request_replan': 'planning',
            'select_step': phase,
        }[action]
        if status == 'paused':
            allowed = action == 'resume'
            return TransitionOption(action=action, target_phase=target, allowed=allowed,
                reason=None if allowed else 'Сначала продолжите задачу после паузы')
        if action == 'resume':
            return TransitionOption(action=action, target_phase=target, allowed=False,
                reason='Задача уже активна')
        if phase == 'done':
            return TransitionOption(action=action, target_phase=target, allowed=False,
                reason='Задача уже завершена')
        if action == 'pause':
            return TransitionOption(action=action, target_phase=target, allowed=True)
        if action == 'accept_plan':
            allowed = phase == 'planning' and candidate is not None
            reason = None if allowed else ('План можно принять только на этапе планирования'
                if phase != 'planning' else 'Сначала обсудите план и получите предложение агента')
        elif action in ('accept_solution', 'select_step'):
            plan_current = (control.approved_plan is not None
                and control.approved_task_revision == task_revision
                and control.approved_invariant_revision == invariant_revision)
            allowed = phase == 'execution' and plan_current and (candidate is not None or action == 'select_step')
            if phase != 'execution':
                reason = 'Реализация доступна только после утверждения плана'
            elif not plan_current:
                reason = 'Утверждённый план устарел или отсутствует; выполните перепланирование'
            elif action == 'accept_solution' and candidate is None:
                reason = 'Сначала получите предложение решения от агента'
            else:
                reason = None
        elif action == 'accept_validation':
            allowed = phase == 'validation' and control.current_solution is not None and candidate is not None
            if phase != 'validation':
                reason = 'Завершение доступно только после этапа проверки'
            elif control.current_solution is None:
                reason = 'Нет актуального решения для проверки'
            elif candidate is None:
                reason = 'Сначала получите отчёт проверки текущего решения'
            else:
                reason = None
        elif action == 'request_changes':
            allowed = phase == 'validation'
            reason = None if allowed else 'Вернуть решение на доработку можно только из проверки'
        elif action == 'request_replan':
            allowed = phase in ('execution', 'validation')
            reason = None if allowed else 'Перепланирование доступно во время реализации или проверки'
        else:
            allowed, reason = False, 'Неизвестное действие'
        return TransitionOption(action=action, target_phase=target, allowed=allowed, reason=reason)

    def options(self, phase, status, candidate, control, task_revision, invariant_revision):
        return [self._option(action, phase, status, candidate, control, task_revision,
            invariant_revision) for action in self.visible_actions]

    def require(self, action, phase, status, candidate, control, task_revision, invariant_revision):
        return self._option(action, phase, status, candidate, control, task_revision, invariant_revision)
