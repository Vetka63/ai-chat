"""DTO публичного HTTP API, не являющиеся командами агента."""

from pydantic import Field

from agent_core.models import StrictModel
from capabilities.context_memory.models import ContextSettings


class CreateConversation(StrictModel):
    """Данные для создания пустого серверного диалога."""

    title: str = Field(default="Новый чат", min_length=1, max_length=120)
    context_settings: ContextSettings = Field(default_factory=ContextSettings)


class SelectModel(StrictModel):
    """Смена разрешённой модели для следующих сообщений чата."""
    model_id: str

