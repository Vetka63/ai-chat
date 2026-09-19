"""Типизированные слои памяти и команды явного сохранения."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator
from capabilities.personalization.models import UserProfile
from capabilities.task_workflow.models import WorkflowWorkspace
from capabilities.invariants.models import InvariantWorkspace


class MemoryModel(BaseModel):
    """Запрещает неизвестные поля на границах памяти."""
    model_config = ConfigDict(extra="forbid")


class StoredMessage(MemoryModel):
    """Сообщение с постоянным ID; внутренние метаданные не отправляются LLM."""
    id: int
    role: Literal["user", "assistant"]
    content: str
    created_at: str


class MemoryCandidate(MemoryModel):
    """Предложение записи: само по себе ещё не является памятью."""
    layer: Literal["working", "long_term"]
    key: str = Field(min_length=1, max_length=80)
    value: str = Field(min_length=1, max_length=20000)
    reason: str = Field(default="", max_length=1000)

    @field_validator("key", "value")
    @classmethod
    def nonblank(cls, value: str) -> str:
        """Нормализует пробелы и запрещает пустую запись."""
        if not value.strip():
            raise ValueError("Запись не может быть пустой")
        return value.strip()


class MemoryEntry(MemoryModel):
    """Подтверждённая запись с версией и происхождением."""
    id: str
    layer: Literal["working", "long_term"]
    key: str
    value: str
    active: bool = True
    revision: int
    source_message_id: int | None = None
    source_excerpt: str
    author: Literal["user", "llm_confirmed"]
    updated_at: str


class MemoryProposal(MemoryCandidate):
    """Ожидающая подтверждения запись, привязанная к исходной версии поля."""
    id: str
    source_message_id: int
    source_excerpt: str
    status: Literal["pending", "accepted", "rejected"]
    base_entry_id: str | None = None
    base_entry_revision: int | None = None
    created_at: str


class TaskMemory(MemoryModel):
    """Карточка рабочей памяти; жизненный цикл хранится отдельным модулем."""
    id: str
    conversation_id: str
    agent_id: str
    profile_id: str
    problem: dict[str, str]
    revision: int


class MemoryProfile(UserProfile):
    """Согласованный снимок профиля внутри рабочего пространства памяти."""


class MemoryWorkspace(MemoryModel):
    """Отдельное представление трёх слоёв и не применённых предложений."""
    workflow: WorkflowWorkspace | None = None
    invariants: InvariantWorkspace = Field(default_factory=InvariantWorkspace)
    task: TaskMemory
    profile: MemoryProfile
    keep_last: int
    history_message_count: int
    short_term: list[StoredMessage]
    working: list[MemoryEntry]
    long_term: list[MemoryEntry]
    proposals: list[MemoryProposal]


class MemoryVersions(MemoryModel):
    """Версии, которые видел клиент; защищают от потери параллельной правки."""
    task_revision: int = Field(ge=1)
    profile_revision: int = Field(ge=1)
    preferences_revision: int = Field(default=1, ge=1)


class SaveMemory(MemoryVersions):
    """Явная запись в выбранном пользователем слое."""
    layer: Literal["working", "long_term"]
    key: str = Field(min_length=1, max_length=80)
    value: str = Field(min_length=1, max_length=20000)
    active: bool = True
    source_message_id: int | None = None

    @field_validator("key", "value")
    @classmethod
    def nonblank(cls, value: str) -> str:
        """Проверяет ручной ввод на границе HTTP, до вызова сервиса."""
        return MemoryCandidate.nonblank(value)


class SaveProblem(MemoryVersions):
    """Замена подтверждённой карточки условия задачи."""
    problem: dict[str, str]


class ProposeMemory(MemoryVersions):
    """Запрос подсказок по одному сохранённому сообщению пользователя."""
    source_message_id: int


class ResolveProposal(MemoryVersions):
    """Явное решение пользователя по одной предложенной записи."""
    action: Literal["accept", "reject"]
