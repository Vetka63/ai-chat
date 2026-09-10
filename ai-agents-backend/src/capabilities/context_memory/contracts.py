"""Порт хранения производной памяти; агенту не требуется знать о SQLite."""
from typing import Protocol
from capabilities.context_memory.models import SummaryState


class SummaryRepository(Protocol):
    """Отдельная от оригинальной переписки сводка, изолированная по агенту и чату."""
    async def get(self, agent_id: str, conversation_id: str) -> SummaryState | None: ...
    async def save(self, agent_id: str, conversation_id: str, state: SummaryState) -> None: ...
