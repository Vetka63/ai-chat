"""Настройки и состояния подключаемых стратегий контекста."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator


ContextMode = Literal["full", "summary", "sliding_window", "sticky_facts", "branching"]


class ContextSettings(BaseModel):
    """Выбранная стратегия; числовые поля обозначают сообщения, не ходы."""
    model_config = ConfigDict(extra="forbid")
    mode: ContextMode = "full"
    keep_last: int = Field(default=10, ge=1, le=100)
    summarize_every: int = Field(default=10, ge=1, le=100)

    @model_validator(mode="after")
    def validate_summary_pairs(self):
        """Summary сжимает целые пары user/assistant; оконные стратегии принимают любое N."""
        if self.mode == "summary" and (self.keep_last < 2 or self.summarize_every < 2
                                       or self.keep_last % 2 or self.summarize_every % 2):
            raise ValueError("Для summary параметры keep_last и summarize_every должны быть чётными и не меньше 2")
        return self


class SummaryState(BaseModel):
    """Сводка префикса [0, covered_messages); оригиналы не удаляются."""
    text: str
    covered_messages: int = Field(ge=1)
    revision: int = Field(ge=1)
    updated_at: str
    model_id: str
    returned_model: str
    run_id: str


class FactsState(BaseModel):
    """Отдельная key-value память с указанием ревизии и источника обновления."""

    facts: dict[str, str] = Field(default_factory=dict)
    revision: int = Field(ge=1)
    updated_at: str
    updated_from_message: int = Field(ge=1)
    model_id: str
    returned_model: str
    run_id: str


class Checkpoint(BaseModel):
    """Неизменяемая точка ветвления в конце выбранного состояния диалога."""

    id: str
    agent_id: str
    conversation_id: str
    title: str
    message_count: int = Field(ge=0)
    created_at: str


class BranchInfo(BaseModel):
    """Связь дочернего диалога с корнем, родителем и checkpoint."""

    root_conversation_id: str
    parent_conversation_id: str | None = None
    checkpoint_id: str | None = None
    branch_name: str | None = None
