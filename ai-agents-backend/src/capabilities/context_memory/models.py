"""Настройки стратегии и отдельное состояние сжатой памяти, без зависимости от HTTP."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field


class ContextSettings(BaseModel):
    """Полная история либо сводка с хвостом; числа обозначают сообщения, не ходы."""
    model_config = ConfigDict(extra="forbid")
    mode: Literal["full", "summary"] = "full"
    keep_last: int = Field(default=10, ge=2, le=100, multiple_of=2)
    summarize_every: int = Field(default=10, ge=2, le=100, multiple_of=2)


class SummaryState(BaseModel):
    """Сводка префикса [0, covered_messages); оригиналы не удаляются."""
    text: str
    covered_messages: int = Field(ge=1)
    revision: int = Field(ge=1)
    updated_at: str
    model_id: str
    returned_model: str
    run_id: str
