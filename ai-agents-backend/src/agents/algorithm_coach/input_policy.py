"""Подготовка сообщения наставнику без изменения его содержательного смысла."""
from agent_core.models import AgentError


class CoachInputPolicy:
    """Нормализует края текста и отклоняет пустое сообщение."""
    def prepare(self, text):
        text = text.strip()
        if not text:
            raise AgentError('empty_message', 'Введите сообщение')
        return text
