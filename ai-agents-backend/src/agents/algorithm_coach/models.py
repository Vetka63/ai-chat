"""Предметные данные алгоритмического наставника; без этапов следующих дней."""
from pydantic import Field
from capabilities.memory_layers.models import MemoryCandidate, MemoryModel


class ProblemSpec(MemoryModel):
    """Карточка подтверждённого пользователем условия задачи."""
    statement: str = Field(default="", max_length=20000)
    inputs: str = Field(default="", max_length=10000)
    outputs: str = Field(default="", max_length=10000)
    constraints: str = Field(default="", max_length=10000)
    examples: str = Field(default="", max_length=10000)


class ProposalPayload(MemoryModel):
    """Ограниченный набор предложений; backend самостоятельно назначает источник."""
    proposals: list[MemoryCandidate] = Field(max_length=5)
