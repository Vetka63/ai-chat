"""Контекст одной ветки; изоляция обеспечивается отдельным conversation_id."""

from capabilities.context_memory.runtime import ContextRequest, PreparedContext
from .common import full_context


class BranchingStrategy:
    """Отправляет только историю активной ветки, не включая родственные ветки."""

    mode = "branching"

    async def prepare(self, request: ContextRequest) -> PreparedContext:
        history = request.conversation.messages
        return PreparedContext(
            full_context(request.system, history, request.text),
            progress={"history_message_count": len(history), "retained_message_count": len(history)},
        )
