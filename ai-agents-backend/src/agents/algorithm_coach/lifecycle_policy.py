"""Ограничения содержимого диалога текущим этапом задачи."""
import re

from agent_core.models import AgentError


class CoachLifecyclePolicy:
    """Не полагается на просьбу в промпте для допуска к следующему этапу."""

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
