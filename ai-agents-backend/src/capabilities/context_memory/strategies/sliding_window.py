"""Стратегия скользящего окна без производной памяти."""

from capabilities.context_memory.runtime import ContextRequest, PreparedContext
from .common import full_context


class SlidingWindowStrategy:
    """Передаёт только последние N сообщений, сохраняя оригиналы в базе."""

    mode = "sliding_window"

    async def prepare(self, request: ContextRequest) -> PreparedContext:
        history = request.conversation.messages
        keep = request.conversation.context_settings.keep_last
        tail = history[-keep:]
        return PreparedContext(
            full_context(request.system, tail, request.text),
            progress={
                "history_message_count": len(history),
                "retained_message_count": len(tail),
                "discarded_message_count": len(history) - len(tail),
            },
        )
