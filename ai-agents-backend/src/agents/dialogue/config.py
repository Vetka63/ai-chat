"""Настройки поведения, принадлежащие только диалоговому агенту."""

from pydantic import ConfigDict, Field

from agent_core.models import StrictModel


class DialogueAgentConfig(StrictModel):
    """Версионированный снимок правил диалогового агента без секретов."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    id: str = "dialogue"
    name: str = "Диалоговый агент"
    description: str = "Диалог с памятью и учётом токенов"
    model: str = Field(min_length=1)
    system_prompt: str = Field(min_length=1)
    temperature: float = Field(default=0.7, ge=0, le=2)
    max_tokens: int = Field(default=1200, ge=1, le=8192)
