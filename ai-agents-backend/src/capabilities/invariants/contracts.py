"""Порты независимого механизма обязательных правил."""
from typing import Protocol


class InvariantRepository(Protocol):
    """Хранит правила и аудит, проверяет владельца и версии в транзакции."""
    async def save(self, agent_id, conversation_id, command, policy): ...
    async def record(self, workspace, check): ...
    async def check_current(self, workspace): ...


class SemanticJudge(Protocol):
    """Проверяет смысл без права изменять правила или состояние задачи."""
    async def evaluate(self, workspace, text, stage, command_id, rules=None): ...
