"""Строгие модели данных на границах агента и LLM-провайдера."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    """Запрещает незаявленные поля во всех публичных командах."""

    model_config = ConfigDict(extra="forbid")


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
    """Единственные данные, которые агент принимает от клиента в День 6."""

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


class AgentError(Exception):
    """Ожидаемая ошибка с безопасным сообщением для пользователя."""

    def __init__(self, code: str, message: str, status: int = 422):
        self.code = code
        self.message = message
        self.status = status
        super().__init__(message)

