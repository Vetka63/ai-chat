"""Fail-closed проверка активных правил до и после основного LLM-вызова."""
from agent_core.models import AgentError, new_id, now

from .models import InvariantCheck, RuleVerdict


class InvariantGuard:
    """Сочетает локальные проверки и отдельного judge, сохраняет краткий аудит."""

    def __init__(self, repository, policy, judge):
        self.repository, self.policy, self.judge = repository, policy, judge

    async def check(self, agent_id, conversation_id, task, workflow, snapshot, text, stage,
                    *, request='', profile=None, user_index=0):
        rules = [rule for rule in snapshot.rules if rule.active]
        if not rules:
            await self.repository.check_current(agent_id, conversation_id, snapshot.revision)
            return None, None, None
        local = self.policy.validate_output(rules, text, workflow.state.phase, stage) if stage != 'input' else []
        run, error_code = None, None
        if local:
            checks = [next((item for item in local if item.rule_id == rule.id),
                RuleVerdict(rule_id=rule.id, verdict='pass', reason='Локальное нарушение не обнаружено'))
                for rule in rules]
        else:
            try:
                checks, run = await self.judge.evaluate(agent_id, conversation_id, task, workflow,
                    rules, text, stage, request, profile, user_index)
            except AgentError as exc:
                error_code = exc.code
                checks = [RuleVerdict(rule_id=rule.id, verdict='uncertain',
                    reason='Judge недоступен или вернул неверный ответ') for rule in rules]
        verdict = 'conflict' if any(c.verdict == 'conflict' for c in checks) else (
            'uncertain' if any(c.verdict == 'uncertain' for c in checks) else 'pass')
        audit = InvariantCheck(id=new_id(), stage=stage, revision=snapshot.revision, verdict=verdict,
            checks=checks, rules=rules, run_id=run.id if run else None, error_code=error_code, created_at=now())
        await self.repository.record(agent_id, conversation_id, audit)
        await self.repository.check_current(agent_id, conversation_id, snapshot.revision)
        if verdict == 'pass':
            return audit, run, None
        labels = {rule.id: rule.label for rule in rules}
        reasons = '; '.join(f'{labels[c.rule_id]}: {c.reason}' for c in checks if c.verdict != 'pass')
        prefix = 'Нельзя выполнить запрос: нарушено обязательное правило. ' if verdict == 'conflict' else (
            'Не удалось подтвердить соблюдение обязательных правил. ')
        return audit, run, prefix + reasons + '. Изменить правила можно в панели задачи.'
