"""Правило включения сохранённого контекста в запрос диалогового агента."""

from agent_core.models import Message


class FullHistoryContextPolicy:
    """Передаёт модели системный промпт, всю историю и новое сообщение по порядку."""

    def build(self, system_prompt: str, history: list[Message], text: str) -> list[Message]:
        return [
            Message(role="system", content=system_prompt),
            *history,
            Message(role="user", content=text),
        ]

