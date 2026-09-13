"""Общие безопасные построители сообщений для стратегий контекста."""

from agent_core.models import Message


def full_context(system: str, history: list[Message], text: str) -> list[Message]:
    """Возвращает system, выбранную историю и текущий вопрос ровно по одному разу."""

    return [Message(role="system", content=system), *history, Message(role="user", content=text)]
