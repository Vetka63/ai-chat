"""DTO публичного HTTP API, не являющиеся командами агента."""

from pydantic import Field, model_validator

from agent_core.models import StrictModel
from capabilities.context_memory.models import ContextSettings


class CreateConversation(StrictModel):
    """Данные для создания пустого серверного диалога."""

    title: str = Field(default="Новый чат", min_length=1, max_length=120)
    context_settings: ContextSettings = Field(default_factory=ContextSettings)
    problem: dict[str, str] | None = None


class SelectModel(StrictModel):
    """Смена разрешённой модели для следующих сообщений чата."""
    model_id: str


class CreateCheckpoint(StrictModel):
    """Название checkpoint, создаваемого в текущем конце диалога."""

    title: str = Field(default="Checkpoint", min_length=1, max_length=80)


class CreateBranches(StrictModel):
    """Ровно две уникальные ветки для учебного эксперимента Дня 10."""

    names: list[str] = Field(min_length=2, max_length=2)

    @model_validator(mode="after")
    def validate_names(self):
        cleaned = [name.strip() for name in self.names]
        if any(not name or len(name) > 60 for name in cleaned) or len(set(cleaned)) != 2:
            raise ValueError("Названия двух веток должны быть непустыми и различаться")
        self.names = cleaned
        return self

