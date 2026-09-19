"""Прикладной вход в автомат с подключаемой предметной политикой."""
from .contracts import TaskUnitOfWork


class TaskWorkflow:
    """Делегирует транзакцию порту, а правила конкретному агенту."""
    def __init__(self, repository: TaskUnitOfWork, policy):
        self.repository, self.policy = repository, policy

    async def change(self, agent_id, conversation_id, operation, command):
        return await self.repository.change(agent_id, conversation_id, operation, command, self.policy)
