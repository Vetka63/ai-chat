"""Интерфейсы, позволяющие подключать новых агентов и LLM-провайдеров."""

from __future__ import annotations

from typing import Any, Literal, Protocol
from capabilities.context_memory.models import ContextSettings

from agent_core.models import (
    AgentCommand,
    AgentInfo,
    AgentResult,
    Completion,
    Conversation,
    ConversationSummary,
    Message,
    ToolCompletion,
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
        max_tokens: int | None,
    ) -> Completion: ...


class ToolLlmClient(Protocol):
    """Отдельный контракт LLM-вызова с объявлениями инструментов."""

    async def complete_with_tools(
        self, messages: list[dict[str, Any]], tools: list[dict[str, Any]], *,
        model: str, temperature: float, max_tokens: int | None, tool_choice: str,
    ) -> ToolCompletion: ...


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
    async def create(self, agent_id: str, title: str, settings: ContextSettings | None = None,
                     mcp_server_ids: list[str] | None = None) -> ConversationSummary: ...
    async def list(self, agent_id: str) -> list[ConversationSummary]: ...
    async def get(self, agent_id: str, conversation_id: str) -> Conversation: ...
    async def delete(self, agent_id: str, conversation_id: str) -> None: ...
    async def select_model(self, agent_id: str, conversation_id: str, model_id: str) -> None: ...
    async def configure_output(self, agent_id: str, conversation_id: str, max_tokens: int | None) -> None: ...
    async def fork(self, source: Conversation, settings: ContextSettings) -> ConversationSummary: ...
    async def append_message(
        self,
        agent_id: str,
        conversation_id: str,
        role: Literal["user", "assistant"],
        content: str,
        mcp_server_ids: list[str] | None = None,
    ) -> None: ...
