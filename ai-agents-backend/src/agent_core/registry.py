"""Реестр независимых агентов приложения."""

from agent_core.contracts import Agent
from agent_core.models import AgentError, AgentInfo


class AgentRegistry:
    """Хранит агентов по идентификатору и скрывает способ их создания от API."""

    def __init__(self, agents: list[Agent]):
        self._agents: dict[str, Agent] = {}
        for agent in agents:
            if agent.info.id in self._agents:
                raise ValueError(f"Duplicate agent id: {agent.info.id}")
            self._agents[agent.info.id] = agent

    def list(self) -> list[AgentInfo]:
        return [agent.info for agent in self._agents.values()]

    def get(self, agent_id: str) -> Agent:
        try:
            return self._agents[agent_id]
        except KeyError as exc:
            raise AgentError("agent_not_found", "Агент не найден", 404) from exc

