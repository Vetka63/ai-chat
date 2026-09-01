from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class ApiModel(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class HistoryMessage(ApiModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=100_000)


class ChatRequest(ApiModel):
    message: str = Field(min_length=1, max_length=100_000)
    profile_id: str | None = Field(default=None, alias="profileId")
    history: list[HistoryMessage] = Field(default_factory=list, max_length=200)


class ChatMeta(ApiModel):
    model: str | None = None
    finish_reason: str | None = Field(default=None, alias="finishReason")


class ChatResponse(ApiModel):
    reply: str
    source: Literal["fallback", "llm"]
    profile_id: str = Field(alias="profileId")
    meta: ChatMeta | None = None
