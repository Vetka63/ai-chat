"""Игровой агент дня 17 с собственным промптом и правилом поиска."""

from agent_core.models import AgentInfo
from agents.mcp_games.lookup_policy import GameCatalogLookupPolicy
from agents.mcp_games.prompts import SYSTEM_PROMPT
from capabilities.mcp_tool_chat.agent import McpToolChatAgent


class McpGamesAgent(McpToolChatAgent):
    ID = "mcp_games"

    def __init__(self, llm, store, catalog, accounting, gateway, events, default_model: str,
                 allowed_server_ids: set[str] | None = None):
        super().__init__(
            llm, store, catalog, accounting, gateway, events, default_model,
            system_prompt=SYSTEM_PROMPT, lookup_policy=GameCatalogLookupPolicy(),
            allowed_server_ids=allowed_server_ids,
        )

    @property
    def info(self) -> AgentInfo:
        return AgentInfo(
            id=self.ID, name="Игровой MCP-агент",
            description="Общается об играх и при необходимости обращается к учебному каталогу через MCP",
            capabilities=["persistent_history", "mcp_tools"],
        )
