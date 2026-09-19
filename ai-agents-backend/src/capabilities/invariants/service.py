"""Fail-closed: сомнение или ошибка проверки не превращаются в разрешение."""
from agent_core.models import AgentError, new_id, now
from .models import InvariantCheck, RuleVerdict


class InvariantGuard:
    """Сочетает предметный валидатор, semantic judge и независимый аудит."""
    def __init__(self, repository, policy, judge):
        self.repository, self.policy, self.judge = repository, policy, judge

    async def check(self, workspace, text, stage, command_id, *, request='', require_solution=False):
        rules = [r for r in workspace.invariants.rules if r.active]
        if not rules:
            return None
        checks = [] if stage == 'input' else self.policy.validate(rules, text, require_solution)
        run, error_code = None, None
        if not checks:
            try:
                checks, run = await self.judge.evaluate(workspace,
                    {'request': request, 'candidate': text} if stage != 'input' else text, stage, command_id)
            except AgentError as exc:
                error_code = exc.code
                checks = [RuleVerdict(rule_id=r.id, verdict='uncertain', reason='Проверка judge недоступна или невалидна; разрешение не получено') for r in rules]
        verdict = 'conflict' if any(c.verdict == 'conflict' for c in checks) else 'uncertain' if any(c.verdict == 'uncertain' for c in checks) else 'pass'
        audit = InvariantCheck(id=new_id(), command_id=command_id, stage=stage,
            revision=workspace.invariants.revision, verdict=verdict, checks=checks, rules=rules,
            run_id=run.id if run else None, error_code=error_code, created_at=now())
        await self.repository.record(workspace, audit)
        await self.repository.check_current(workspace)
        if verdict != 'pass':
            by_id = {r.id: r for r in rules}
            violations = [f'«{by_id[c.rule_id].label}» [{c.rule_id}]: {c.reason}' for c in checks if c.verdict != 'pass']
            message = ('Запрос или кандидат конфликтует с обязательными правилами. ' if verdict == 'conflict' else 'Не удалось подтвердить соблюдение обязательных правил. ')
            message += ' '.join(violations) + ' Продолжите в рамках указанных правил либо явно измените их в панели «Обязательные правила».'
            exc = AgentError('invariant_'+verdict, message, 422 if verdict == 'conflict' else 503)
            exc.details = {'type': 'policy_refusal', 'check': audit.model_dump(), 'source': 'policy', 'model': None}
            raise exc
        return run
