"""Проверка правил отдельным вызовом DeepSeek Pro с обязательной JSON-схемой."""
import asyncio
import json
import logging
from pathlib import Path

from agent_core.models import AgentError, Message
from capabilities.invariants.models import JudgePayload

logger = logging.getLogger(__name__)


class InvariantJudge:
    """Fail-closed: неполный ответ модели не превращается в разрешение."""

    def __init__(self, calls, accounting, catalog, model='deepseek-v4-pro'):
        self.calls, self.accounting, self.catalog, self.model = calls, accounting, catalog, model
        self.prompt = (Path(__file__).parents[1] / 'prompts' / 'invariant_judge.txt').read_text(encoding='utf-8')

    async def evaluate(self, agent_id, conversation_id, task, workflow, rules, text, stage,
                       request, profile, user_index):
        spec = self.catalog.get(self.model)
        payload = json.dumps({'stage': stage, 'rules': [rule.model_dump() for rule in rules],
            'problem': task.problem, 'workflow': workflow.state.model_dump(),
            'profile': profile.model_dump() if profile else None,
            'request': request, 'candidate': text}, ensure_ascii=False)
        system = self.prompt + '\nПроверь ID по порядку: ' + json.dumps([rule.id for rule in rules])
        messages = [Message(role='system', content=system), Message(role='user', content=payload)]
        max_tokens = min(1800, spec.max_output_tokens)
        estimate = await asyncio.to_thread(self.accounting.estimate, messages, [], payload, spec, max_tokens)
        async with self.calls.invoke(agent_id=agent_id, conversation_id=conversation_id,
            messages=messages, spec=spec, estimate=estimate, user_index=user_index,
            temperature=0, max_tokens=max_tokens, purpose='invariant_' + stage,
            memory_context={'task_id': task.id, 'rule_ids': [rule.id for rule in rules]}) as (completion, run):
            try:
                if completion.finish_reason not in (None, 'stop'):
                    raise ValueError('truncated')
                content = completion.content.strip()
                if content.startswith('```') and content.endswith('```'):
                    content = content[3:-3].strip()
                    if content.lower().startswith('json'):
                        content = content[4:].strip()
                result = JudgePayload.model_validate_json(content)
                ids = [check.rule_id for check in result.checks]
                if ids != [rule.id for rule in rules]:
                    raise ValueError('missing, duplicate or reordered rule IDs')
            except ValueError as exc:
                logger.warning('invariant_judge_invalid run=%s stage=%s finish=%s chars=%s',
                    run.id, stage, completion.finish_reason, len(completion.content))
                raise AgentError('invalid_invariant_judge', 'Judge вернул неверный JSON; результат не разрешён', 502) from exc
        return result.checks, run
