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
        r'как(?:ой|ов)\s+(?:у\s+нас\s+)?план',
        r'(?:мы|задача)\s+(?:сейчас\s+)?(?:не\s+)?на\s+(?:шаге|этапе|фазе)',
        r'где\s+(?:мы|задача)\s+(?:сейчас\s+)?(?:находимся|остановились)',
        r'(?:план|решение|проверка)\s+(?:уже\s+)?(?:есть|принят|утвержд|сохран)',
        r'(?:ты|мы).{0,40}(?:сделал|сделали|провёл|провели).{0,30}(?:проверк|валидац)',
    )]
    plan_requests = [re.compile(pattern, re.IGNORECASE | re.DOTALL) for pattern in (
        r'\b(?:составь|составим|составить|предложи|подготовь|сформируй|доработай|измени|накидай|накидаем|набросай)\b.{0,80}\bплан',
        r'\bкак\s+(?:будем|лучше)\s+решать\b',
    )]
    solution_requests = [re.compile(pattern, re.IGNORECASE | re.DOTALL) for pattern in (
        r'\b(?:напиши|сгенерируй|покажи|дай|выдай|предложи)\b.{0,80}\b(?:код|решение|функци\w*|класс\w*)',
        r'\bреализ(?:уй|уем|овать|ация|ацию)\b',
        r'\b(?:перейд\w*|приступ\w*|продолж\w*)\b.{0,60}\b(?:решени|реализац|код|шаг)',
        r'\b(?:implement|write|provide|generate)\b.{0,50}\b(?:code|implementation|function|class|solution)\b',
    )]
    validation_requests = [re.compile(pattern, re.IGNORECASE | re.DOTALL) for pattern in (
        r'\b(?:проверь|проверить|протестируй|валидируй)\b',
        r'\b(?:проведи|сделай)\b.{0,40}\b(?:проверку|валидацию|тестирование)',
        r'\b(?:всё|все)\b.{0,30}\b(?:правильно|корректно)\b',
        r'\b(?:validate|verify|test|review)\b.{0,60}\b(?:solution|code|implementation)?\b',
    )]

    implementation_requests = [re.compile(pattern, re.IGNORECASE | re.DOTALL) for pattern in (
        r'\b(?:напиши|сгенерируй|покажи|дай)\s+(?:мне\s+)?(?:полное\s+|готов(?:ое|ый|ую)\s+)?(?:решение|код|функци\w*|класс\w*)',
        r'\bреализ(?:уй|уем|овать|ация|ацию)\b',
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
        r'^\s*(?:const|let|var)\s+[A-Za-z_]\w*\s*=.*=>',
        r'^\s*(?:fun|func)\s+[A-Za-z_]\w*\s*\(',
        r'^\s*#include\s*[<"]',
    )]
    validation_output = [re.compile(pattern, re.IGNORECASE) for pattern in (
        r'\bпроверк\w*\b', r'\bвалидац\w*\b', r'\bтест\w*\b',
        r'\bкоррект\w*\b', r'\bошиб\w*\b', r'\bсложност\w*\b',
        r'\bсоответств\w*\b', r'\b(?:validation|verification|tests?)\b',
    )]
    plan_heading = re.compile(r'^(?:#{1,6}\s*)?(?:\*\*)?(?:план(?:\s+решения)?|шаги)(?:\*\*)?\s*$',
        re.IGNORECASE | re.MULTILINE)
    numbered_step = re.compile(r'^\s*\d+[.)]\s+\S+', re.MULTILINE)
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
        phase, requested = workspace.state.phase, self.requested_stage(text)
        if phase == 'planning' and (requested == 'execution' or (requested is None
                and any(rule.search(text) for rule in self.implementation_requests))):
            return self._refusal()
        if phase == 'planning' and requested == 'validation':
            return ('Проверка пока недоступна: сначала подготовьте и подтвердите план, '
                'затем сохраните решение на этапе реализации.')
        if phase == 'execution' and requested == 'validation':
            return ('Сначала сохраните актуальное решение в панели задачи. После перехода '
                'в `validation` я проверю именно сохранённый артефакт.')
        if phase == 'execution' and requested == 'planning':
            return ('Сейчас действует утверждённый план. Чтобы заменить его, нажмите '
                '«Перепланировать» и укажите причину возврата в `planning`.')
        if phase == 'validation' and requested == 'planning':
            return ('Новый план нельзя подменить отчётом проверки. Используйте действие '
                '«Перепланировать», чтобы явно вернуться в `planning`.')
        if phase == 'validation' and requested == 'execution':
            return ('Текущее решение уже передано на проверку. Чтобы изменить код, нажмите '
                '«Вернуть на доработку» и продолжите работу в `execution`.')
        return None

    def output_issue(self, request, text, workspace):
        """Обнаруживает ответ LLM, который нельзя считать результатом запрошенного этапа."""
        phase = workspace.state.phase
        if phase == 'planning' and any(rule.search(text) for rule in self.implementation_output):
            return ('Backend отклонил предыдущий ответ: в planning нельзя выдавать готовый код. '
                'Верни нумерованный план без реализации.')
        if self.requested_stage(request) != phase:
            return None
        valid = {
            # Структуру плана дополнительно разбирает UI. На backend достаточно
            # гарантировать, что planning-ответ не маскирует готовую реализацию.
            'planning': True,
            'execution': any(rule.search(text) for rule in self.implementation_output),
            'validation': any(rule.search(text) for rule in self.validation_output),
            'done': False,
        }[phase]
        if not valid:
            expected = {
                'planning': 'нумерованный план решения',
                'execution': 'реализацию алгоритма с блоком программного кода',
                'validation': 'отчёт проверки сохранённого решения',
                'done': 'никакой новый результат',
            }[phase]
            return (f'Backend отклонил предыдущий ответ: актуальная фаза — {phase}. '
                f'Нужно выдать {expected}, не определяя фазу по старой переписке.')
        return None

    @staticmethod
    def output_failure(workspace):
        """Безопасный ответ, когда LLM дважды не соблюла контракт текущей фазы."""
        if workspace.state.phase == 'planning':
            return CoachLifecyclePolicy._refusal()
        return (f'LLM дважды вернула ответ, не соответствующий этапу `{workspace.state.phase}`. '
            'Черновик не сохранён; состояние задачи не изменилось. Повторите запрос.')

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

    def requested_stage(self, text):
        """Классифицирует только явный запрос результата этапа, а не свободный диалог."""
        if any(rule.search(text) for rule in self.validation_requests):
            return 'validation'
        if any(rule.search(text) for rule in self.plan_requests):
            return 'planning'
        if any(rule.search(text) for rule in self.solution_requests):
            return 'execution'
        return None

    def output_stage(self, text):
        """Определяет тип структурированного результата по самому ответу модели."""
        if any(rule.search(text) for rule in self.implementation_output):
            return 'execution'
        if self.plan_heading.search(text) and self.numbered_step.search(text):
            return 'planning'
        if any(rule.search(text) for rule in self.validation_output):
            return 'validation'
        return None

    def can_be_candidate(self, request, output, workspace):
        """Черновик должен совпадать с фазой по запросу или по структуре ответа."""
        phase = workspace.state.phase
        explicitly_requested = self.requested_stage(request) == phase
        inferred_first_draft = (workspace.state.candidate_message_id is None
            and self.output_stage(output) == phase)
        return explicitly_requested or inferred_first_draft

    def validate_artifact(self, action, text):
        """Не позволяет отредактированному в UI тексту маскироваться под другой артефакт."""
        if action == 'accept_solution' and not any(rule.search(text) for rule in self.implementation_output):
            raise AgentError('invalid_stage_artifact',
                'Решение должно содержать реализацию алгоритма или блок программного кода', 422)
        if action == 'accept_validation' and not any(rule.search(text) for rule in self.validation_output):
            raise AgentError('invalid_stage_artifact',
                'Отчёт проверки должен описывать проверку, тесты, корректность или найденные ошибки', 422)
