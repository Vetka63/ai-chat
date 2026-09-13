"""Порты стратегий и производной памяти; домен не зависит от SQLite."""
from typing import Protocol
from capabilities.context_memory.models import Checkpoint, FactsState, SummaryState


class SummaryRepository(Protocol):
    """Отдельная от оригинальной переписки сводка, изолированная по агенту и чату."""
    async def get(self, agent_id: str, conversation_id: str) -> SummaryState | None: ...
    async def save(self, agent_id: str, conversation_id: str, state: SummaryState) -> None: ...


class FactsRepository(Protocol):
    """Хранит одну актуальную ревизию key-value facts на диалог."""

    async def get(self, agent_id: str, conversation_id: str) -> FactsState | None: ...
    async def save(self, agent_id: str, conversation_id: str, state: FactsState) -> None: ...


class BranchRepository(Protocol):
    """Хранит checkpoint и создаёт из него независимые дочерние диалоги."""

    async def create_checkpoint(self, agent_id: str, conversation_id: str, title: str) -> Checkpoint: ...
    async def list_checkpoints(self, agent_id: str, conversation_id: str) -> list[Checkpoint]: ...
    async def create_branches(self, agent_id: str, checkpoint_id: str, names: list[str]): ...
