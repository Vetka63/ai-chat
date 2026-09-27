"""Типизированные артефакты длинного MCP-сценария."""

from typing import Any, Literal

from pydantic import BaseModel, Field


ArtifactKind = Literal["search", "collection", "summary", "draft", "report"]


class Artifact(BaseModel):
    id: str
    agent_id: str
    conversation_id: str
    kind: ArtifactKind
    title: str
    payload: dict[str, Any]
    source_ids: list[str] = Field(default_factory=list)
    user_index: int
    created_at: str
