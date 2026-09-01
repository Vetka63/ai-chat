from dataclasses import dataclass
from typing import Any, Protocol

import httpx
from pydantic import BaseModel, ValidationError

from app.core.settings import Settings
from app.domain.agents import AgentProfile
from app.domain.errors import (
    InvalidLlmResponseError,
    LlmUnavailableError,
    MissingApiKeyError,
)


@dataclass(frozen=True)
class LlmResult:
    content: str
    model: str
    finish_reason: str | None


class LlmClient(Protocol):
    async def complete(
        self,
        profile: AgentProfile,
        messages: list[dict[str, str]],
    ) -> LlmResult: ...


class _ResponseMessage(BaseModel):
    content: str | None = None


class _Choice(BaseModel):
    message: _ResponseMessage
    finish_reason: str | None = None


class _CompletionResponse(BaseModel):
    model: str
    choices: list[_Choice]


class DeepSeekClient:
    def __init__(self, settings: Settings, http_client: httpx.AsyncClient):
        self._settings = settings
        self._http_client = http_client

    async def complete(
        self,
        profile: AgentProfile,
        messages: list[dict[str, str]],
    ) -> LlmResult:
        api_key = self._settings.llm_api_key.get_secret_value().strip()
        if not api_key:
            raise MissingApiKeyError

        payload: dict[str, Any] = {
            "model": profile.deepseek.model or self._settings.llm_model,
            "messages": messages,
        }
        optional_values = {
            "reasoning_effort": profile.deepseek.reasoning_effort,
            "temperature": profile.deepseek.temperature,
            "top_p": profile.deepseek.top_p,
            "max_tokens": profile.deepseek.max_tokens,
            "stop": profile.deepseek.stop,
        }
        payload.update({
            key: value
            for key, value in optional_values.items()
            if value is not None
        })
        if profile.deepseek.thinking is not None:
            payload["thinking"] = {"type": profile.deepseek.thinking}

        try:
            response = await self._http_client.post(
                "/chat/completions",
                headers={"Authorization": f"Bearer {api_key}"},
                json=payload,
            )
            response.raise_for_status()
            parsed = _CompletionResponse.model_validate(response.json())
        except (httpx.HTTPError, ValueError, ValidationError) as exc:
            raise LlmUnavailableError from exc

        if not parsed.choices:
            raise InvalidLlmResponseError
        choice = parsed.choices[0]
        content = (choice.message.content or "").strip()
        if not content:
            raise InvalidLlmResponseError

        return LlmResult(
            content=content,
            model=parsed.model,
            finish_reason=choice.finish_reason,
        )
