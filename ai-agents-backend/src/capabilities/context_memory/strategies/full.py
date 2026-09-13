"""Контрольная стратегия полной истории."""

from capabilities.context_memory.runtime import ContextRequest, PreparedContext
from .common import full_context


class FullHistoryStrategy:
    """Отправляет модели всю сохранённую историю без преобразований."""

    mode = "full"

    async def prepare(self, request: ContextRequest) -> PreparedContext:
        return PreparedContext(full_context(request.system, request.conversation.messages, request.text))
