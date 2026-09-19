"""Предметный semantic judge с отдельной моделью и проверкой полноты ID правил."""
import asyncio
import json
import logging
from pathlib import Path
from agent_core.models import AgentError, Message
from capabilities.invariants.models import JudgePayload

logger = logging.getLogger(__name__)


class InvariantJudge:
    """Проверяет схему ответа judge; не имеет права изменять состояние задачи."""
    def __init__(self, calls, accounting, catalog, model, max_tokens):
        self.calls, self.accounting, self.catalog = calls, accounting, catalog
        self.model, self.max_tokens = model, max_tokens
        self.prompt = (Path(__file__).parents[1]/'prompts'/'invariant_judge.txt').read_text(encoding='utf-8')

    async def evaluate(self, workspace, text, stage, command_id):
        rules = [r for r in workspace.invariants.rules if r.active]
        spec = self.catalog.get(self.model)
        payload = {'stage': stage, 'rules': [r.model_dump() for r in rules],
            'profile': workspace.profile.model_dump(), 'problem': workspace.task.problem,
            'state': workspace.workflow.state.model_dump(), 'content_to_check': text}
        serialized = json.dumps(payload, ensure_ascii=False)
        # Список ID повторён в доверенной инструкции: нельзя закончить на первом conflict.
        instruction = self.prompt + '\nВерни checks ровно для этих ID в указанном порядке: ' + json.dumps([r.id for r in rules])
        messages = [Message(role='system', content=instruction), Message(role='user', content=serialized)]
        estimate = await asyncio.to_thread(self.accounting.estimate, messages, [], serialized, spec, self.max_tokens)
        async with self.calls.invoke(agent_id=workspace.task.agent_id, conversation_id=workspace.task.conversation_id,
            messages=messages, spec=spec, estimate=estimate, user_index=workspace.history_message_count,
            temperature=0, max_tokens=self.max_tokens, purpose='invariant_'+stage,
            memory_context={'task_id': workspace.task.id, 'command_id': command_id,
                'invariant_revision': workspace.invariants.revision, 'rules': payload['rules']}) as (completion, run):
            try:
                if completion.finish_reason not in (None, 'stop'):
                    raise ValueError('truncated')
                result = JudgePayload.model_validate_json(completion.content)
                ids = [c.rule_id for c in result.checks]
                if len(ids) != len(rules) or set(ids) != {r.id for r in rules}:
                    raise ValueError('missing/unknown/duplicate IDs')
            except ValueError as exc:
                # Не логируем prompt, профиль и кандидата; достаточно типа сбоя и корреляции.
                logger.warning('invariant_judge_invalid command=%s stage=%s run=%s finish=%s chars=%s error_type=%s',
                    command_id, stage, run.id, completion.finish_reason, len(completion.content), type(exc).__name__)
                raise AgentError('invalid_invariant_judge', 'Judge вернул неполный или неверный JSON; кандидат не разрешён', 502) from exc
        return result.checks, run
