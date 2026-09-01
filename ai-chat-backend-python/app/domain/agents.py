from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints


AgentId = Annotated[
    str,
    StringConstraints(pattern=r"^[a-z][a-z0-9-]{1,63}$"),
]


class InputPolicyConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    max_message_length: int = Field(default=10_000, ge=1, le=100_000)
    max_history_messages: int = Field(default=30, ge=0, le=200)
    allowed_roles: tuple[Literal["user", "assistant"], ...] = ("user", "assistant")


class DeepSeekConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    model: str | None = None
    thinking: Literal["enabled", "disabled"] | None = None
    reasoning_effort: Literal["low", "high", "max"] | None = None
    temperature: float | None = Field(default=None, ge=0, le=2)
    top_p: float | None = Field(default=None, ge=0, le=1)
    max_tokens: int | None = Field(default=None, ge=1)
    stop: str | list[str] | None = None


class OutputPolicyConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: Literal["text"] = "text"


class AgentProfile(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: AgentId
    name: str = Field(min_length=1, max_length=100)
    description: str = Field(min_length=1, max_length=300)
    version: int = Field(default=1, ge=1)
    enabled: bool = True
    system_prompt: str = Field(min_length=1, max_length=50_000)
    input_policy: InputPolicyConfig = Field(default_factory=InputPolicyConfig)
    deepseek: DeepSeekConfig = Field(default_factory=DeepSeekConfig)
    output_policy: OutputPolicyConfig = Field(default_factory=OutputPolicyConfig)


class AgentSummary(BaseModel):
    id: AgentId
    name: str
    description: str
    version: int

    @classmethod
    def from_profile(cls, profile: AgentProfile) -> "AgentSummary":
        return cls(
            id=profile.id,
            name=profile.name,
            description=profile.description,
            version=profile.version,
        )
