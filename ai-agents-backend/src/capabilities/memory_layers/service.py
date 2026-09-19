"""Применение политики перед операциями репозитория; чтение не меняет память."""
from .contracts import MemoryRepository, MemoryWritePolicy
from .models import MemoryCandidate, SaveMemory, SaveProblem


class LayeredMemory:
    """Связывает общий механизм записи с правилами конкретного агента."""
    def __init__(self, repository: MemoryRepository, policy: MemoryWritePolicy):
        self.repository, self.policy = repository, policy

    async def save(self, agent_id: str, conversation_id: str, command: SaveMemory):
        candidate = MemoryCandidate(layer=command.layer, key=command.key, value=command.value)
        self.policy.validate_entry(candidate)
        command = command.model_copy(update={"key": candidate.key, "value": candidate.value})
        await self.repository.save_entry(agent_id, conversation_id, command)

    async def save_problem(self, agent_id: str, conversation_id: str, command: SaveProblem):
        problem = self.policy.validate_problem(command.problem)
        await self.repository.save_problem(agent_id, conversation_id, problem, command)
