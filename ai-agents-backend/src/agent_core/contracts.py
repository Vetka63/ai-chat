"""Интерфейсы, позволяющие подключать новых агентов и LLM-провайдеров."""

from typing import Protocol

from agent_core.models import AgentCommand, AgentInfo, AgentResult, Completion, Message


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

