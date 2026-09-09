"""Строгие модели данных на границах агента и LLM-провайдера."""

from datetime import UTC, datetime
from typing import Literal
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    """Запрещает незаявленные поля во всех публичных командах."""

    model_config = ConfigDict(extra="forbid")


def new_id() -> str:
    """Создаёт независимый идентификатор диалога."""

    return str(uuid4())


def now() -> str:
    """Возвращает время UTC в переносимом ISO-формате."""

    return datetime.now(UTC).isoformat()


class Message(StrictModel):
    """Одно сообщение, подготовленное сервером для LLM API."""

    role: Literal["system", "user", "assistant"]
    content: str


class Completion(StrictModel):
    """Нормализованный ответ LLM, не зависящий от формата DeepSeek."""

    content: str
    model: str
    source: Literal["llm", "demo"] = "llm"


class AgentCommand(StrictModel):
    """Команда агенту: идентификатор серверного диалога и новое сообщение."""

    conversation_id: str = Field(min_length=1, max_length=64)
    message: str = Field(min_length=1, max_length=10_000)


class AgentResult(StrictModel):
    """Унифицированный результат любого агента для HTTP или CLI."""

    agent_id: str
    reply: str
    model: str
    source: Literal["llm", "demo"] = "llm"


class AgentInfo(StrictModel):
    """Безопасные публичные сведения для выбора агента в интерфейсе."""

    id: str
    name: str
    description: str


class ConversationSummary(StrictModel):
    """Короткое представление сохранённого диалога для боковой панели."""

    id: str
    agent_id: str
    title: str
    created_at: str
    updated_at: str


class Conversation(ConversationSummary):
    """Диалог с упорядоченной историей сообщений."""

    messages: list[Message]


class AgentError(Exception):
    """Ожидаемая ошибка с безопасным сообщением для пользователя."""

    def __init__(self, code: str, message: str, status: int = 422):
        self.code = code
        self.message = message
        self.status = status
        super().__init__(message)
