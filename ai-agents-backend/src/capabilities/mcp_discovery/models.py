"""Модели ответа для проверки MCP-соединения и списка инструментов."""

from typing import Any, Literal

from pydantic import BaseModel, Field


class McpServerSummary(BaseModel):
    id: str
    name: str
    description: str
    transport: Literal["stdio"] = "stdio"


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
