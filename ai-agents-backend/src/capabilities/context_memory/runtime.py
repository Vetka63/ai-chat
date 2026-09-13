"""DTO одного прохода стратегии; отделены от постоянных моделей памяти."""

from dataclasses import dataclass, field
from typing import Any

from agent_core.models import Message
from capabilities.context_memory.models import FactsState, SummaryState
from capabilities.token_accounting.models import RunRecord


@dataclass
class ContextRequest:
    """Все данные, доступные стратегии при подготовке одного LLM-вызова."""

    conversation: Any
    system: str
    text: str
    model_id: str
    generate: bool


@dataclass
class PreparedContext:
    """Готовый контекст и наблюдаемые побочные результаты стратегии."""

    messages: list[Message]
    summary: SummaryState | None = None
    facts: FactsState | None = None
    pending_summary: bool = False
    new_runs: list[RunRecord] = field(default_factory=list)
    progress: dict | None = None
    warnings: list[str] = field(default_factory=list)
