"""Неизменяемое правило стадии: нельзя отключить его через список правил задачи."""
import re
from capabilities.invariants.models import TaskInvariant, RuleVerdict


class CoachStagePolicy:
    """Добавляет фазовое правило в существующий judge без отдельной цепочки вызовов."""
    descriptions = {
        'planning': 'Разрешены объяснение, обсуждение подхода и план. Запрещены готовая реализация, исполняемый код и подробный псевдокод, заменяющий реализацию, даже внутри объяснения. Сначала пользователь сохраняет и утверждает план кнопкой.',
        'execution': 'Разрешены объяснение и реализация в рамках утверждённого плана. Нельзя объявить задачу завершённой или проверку принятой. Для проверки пользователь сохраняет решение и явно передаёт его на validation.',
        'validation': 'Разрешён анализ текущего решения и отчёт проверки. Нельзя выдавать новую исправленную реализацию: сначала явный возврат на доработку. Нельзя утверждать, что приложение реально запускало код. Нельзя объявить done без явного принятия отчёта пользователем. У отчёта blocking_issues должен честно отражать описанные блокирующие ошибки: нельзя написать о нерешённой ошибке и передать пустой список.',
        'done': 'Задача завершена, новые ответы и артефакты запрещены.',
    }

    def rules(self, workspace):
        state = workspace.workflow.state
        return [TaskInvariant(id='workflow-stage', kind='semantic', label='Допустимое действие этапа',
            value='Текущая фаза: '+state.phase+'. '+self.descriptions[state.phase], revision=state.revision, author='system')]

    def validate(self, workspace, text):
        if workspace.workflow.state.phase in ('planning', 'validation') and re.search(
            r'```\s*(?:python|py|java|javascript|js|c\+\+|cpp|csharp|go|rust)|(?m:^\s*(?:def |class |function |public static |import |from ))', text):
            return [RuleVerdict(rule_id='workflow-stage', verdict='conflict', reason='Реализация допустима только на этапе execution после утверждения плана. Обсудите подход текстом или смените этап явной командой.')]
        return []
