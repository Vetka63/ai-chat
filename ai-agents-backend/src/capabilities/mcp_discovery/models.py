"""Модели ответа для проверки MCP-соединения и списка инструментов."""

from typing import Any, Literal

from pydantic import BaseModel, Field


class McpServerSummary(BaseModel):
    id: str
    name: str
    description: str
    transport: Literal["stdio", "streamable_http"] = "stdio"
    chat_enabled: bool = False


class McpToolSummary(BaseModel):
    name: str
    title: str
    description: str | None = None
    input_schema: dict[str, Any] = Field(default_factory=dict)


class McpDiscoveryResult(BaseModel):
    server: McpServerSummary
    server_name: str
    protocol_version: str
    tools: list[McpToolSummary]


class McpToolExecution(BaseModel):
    """Проверенный результат одного реального вызова MCP-инструмента."""

    server_id: str
    tool_name: str
    structured_content: dict[str, Any]


class McpToolEvent(BaseModel):
    """Сохранённый след вызова инструмента, привязанный к вопросу в чате."""

    id: str
    agent_id: str
    conversation_id: str
    user_index: int
    created_at: str
    server_id: str
    tool_name: str
    arguments: dict[str, Any]
    result: dict[str, Any]
    status: Literal["success", "error"]
