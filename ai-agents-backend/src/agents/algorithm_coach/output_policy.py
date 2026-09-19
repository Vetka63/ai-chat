"""Проверка пользовательского ответа и JSON предложений памяти."""
import json
from pydantic import ValidationError
from agent_core.models import AgentError
from .models import ProposalPayload


class CoachOutputPolicy:
    """Не публикует пустые/обрезанные ответы и не применяет невалидные предложения."""
    def present(self, completion):
        if not completion.content.strip():
            raise AgentError('empty_response', 'Модель вернула пустой ответ. Сообщение сохранено', 502)
        if completion.finish_reason not in (None, 'stop'):
            raise AgentError('incomplete_response', 'Ответ модели не завершён. Увеличьте лимит ответа или повторите запрос', 502)
        return completion.content.strip()

    def proposals(self, completion, policy):
        text = self.present(completion)
        if text.startswith('```') and text.endswith('```'):
            text = text[3:-3].strip()
            if text.startswith('json'):
                text = text[4:].strip()
        try:
            candidates = ProposalPayload.model_validate(json.loads(text)).proposals
            if len({(p.layer, p.key) for p in candidates}) != len(candidates):
                raise ValueError('duplicate targets')
        except (ValueError, ValidationError) as exc:
            raise AgentError('invalid_memory_proposals', 'Модель вернула неверный формат предложений. Подтверждённая память не изменена', 502) from exc
        try:
            for item in candidates:
                policy.validate_entry(item)
        except AgentError as exc:
            raise AgentError('invalid_memory_proposals', 'Предложение модели не прошло правила памяти. Сохранённые записи не изменены', 502) from exc
        return candidates
