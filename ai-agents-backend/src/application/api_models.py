"""DTO публичного HTTP API, не являющиеся командами агента."""

from pydantic import Field

from agent_core.models import StrictModel


class CreateConversation(StrictModel):
    """Данные для создания пустого серверного диалога."""

    title: str = Field(default="Новый чат", min_length=1, max_length=120)

