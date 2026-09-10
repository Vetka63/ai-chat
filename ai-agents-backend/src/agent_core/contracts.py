"""Интерфейсы, позволяющие подключать новых агентов и LLM-провайдеров."""

from typing import Literal, Protocol

from agent_core.models import (
    AgentCommand,
    AgentInfo,
    AgentResult,
    Completion,
    Conversation,
    ConversationSummary,
    Message,
)


class Agent(Protocol):
    """Общий контракт агента: сведения о нём и один изолированный запуск."""

    @property
    def info(self) -> AgentInfo: ...

    async def run(self, command: AgentCommand) -> AgentResult: ...


class LlmClient(Protocol):
    """Порт к внешней модели; агент не зависит от HTTP-библиотеки."""

    async def complete(
        self,
        messages: list[Message],
        *,
        model: str,
        temperature: float,
        max_tokens: int,
    ) -> Completion: ...


class InputPolicy(Protocol):
    """Правило подготовки пользовательского текста конкретного агента."""

    def prepare(self, text: str) -> str: ...


class InputValidator(Protocol):
    """Проверка ввода до платного вызова модели."""

    def validate(self, text: str) -> None: ...


class OutputPolicy(Protocol):
    """Правило подготовки результата конкретного агента."""

    def present(self, text: str) -> str: ...


class OutputValidator(Protocol):
    """Проверка ответа модели до возврата пользователю."""

    def validate(self, text: str) -> None: ...


class ContextPolicy(Protocol):
    """Определяет, какую сохранённую историю конкретный агент отправляет модели."""

    def build(self, system_prompt: str, history: list[Message], text: str) -> list[Message]: ...


class ConversationStore(Protocol):
    """Порт постоянного хранилища, не привязанный к SQLite в коде агента."""

    async def initialize(self) -> None: ...
    async def create(self, agent_id: str, title: str) -> ConversationSummary: ...
    async def list(self, agent_id: str) -> list[ConversationSummary]: ...
    async def get(self, agent_id: str, conversation_id: str) -> Conversation: ...
    async def delete(self, agent_id: str, conversation_id: str) -> None: ...
    async def select_model(self, agent_id: str, conversation_id: str, model_id: str) -> None: ...
    async def append_message(
        self,
        agent_id: str,
        conversation_id: str,
        role: Literal["user", "assistant"],
        content: str,
    ) -> None: ...
