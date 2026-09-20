"""Простые локальные проверки; смысловые ограничения оценивает отдельный judge."""
import re

from agent_core.models import AgentError
from capabilities.invariants.models import RuleVerdict


class CoachInvariantPolicy:
    """Не исполняет пользовательский код и не делает вид, что доказала его корректность."""

    @staticmethod
    def validate_rules(rules):
        active_languages = [rule for rule in rules if rule.active and rule.kind == 'language']
        if len(active_languages) > 1:
            raise AgentError('invalid_invariants', 'Допустимо только одно активное правило языка кода', 422)

    @staticmethod
    def validate_output(rules, text, phase, stage):
        # Ответ модели может цитировать чужой код как отрицательный пример;
        # локально отклоняем только явно сохраняемое решение, остальное — judge.
        if phase != 'execution' or stage != 'artifact':
            return []
        language = next((rule for rule in rules if rule.kind == 'language'), None)
        if not language:
            return []
        expected = language.value.strip().lower()
        aliases = {'python': {'python', 'py', 'python3'}, 'javascript': {'javascript', 'js'},
            'typescript': {'typescript', 'ts'}, 'java': {'java'},
            'c++': {'cpp', 'c++', 'cc'}, 'c#': {'csharp', 'c#', 'cs'}}
        allowed = aliases.get(expected, {expected})
        blocks = re.findall(r'```([^\n`]*)\n[\s\S]*?```', text)
        wrong = [tag.strip().lower() for tag in blocks if tag.strip() and tag.strip().lower() not in allowed]
        if wrong:
            return [RuleVerdict(rule_id=language.id, verdict='conflict',
                reason=f'Блок кода помечен языком {wrong[0]}, требуется {language.value}')]
        return []
