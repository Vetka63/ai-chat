import asyncio
import json

import httpx
import pytest

from app.domain.errors import MissingApiKeyError
from app.llm.deepseek import DeepSeekClient


def test_sends_profile_parameters_to_deepseek(llm_settings, registry) -> None:
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["authorization"] = request.headers["Authorization"]
        captured["payload"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "model": "deepseek-test",
                "choices": [
                    {
                        "message": {"role": "assistant", "content": "Готово"},
                        "finish_reason": "stop",
                    }
                ],
            },
        )

    async def run_request():
        async with httpx.AsyncClient(
            base_url="https://api.deepseek.com",
            transport=httpx.MockTransport(handler),
        ) as http_client:
            client = DeepSeekClient(llm_settings, http_client)
            return await client.complete(
                registry.get("recipe"),
                [{"role": "user", "content": "Салат"}],
            )

    result = asyncio.run(run_request())

    assert result.content == "Готово"
    assert result.finish_reason == "stop"
    assert captured["authorization"] == "Bearer test-key"
    assert captured["payload"]["model"] == "deepseek-test"
    assert captured["payload"]["thinking"] == {"type": "disabled"}
    assert captured["payload"]["temperature"] == 0.4
    assert captured["payload"]["max_tokens"] == 1000


def test_requires_api_key(fallback_settings, registry) -> None:
    async def run_request():
        async with httpx.AsyncClient(base_url="https://api.deepseek.com") as http_client:
            client = DeepSeekClient(fallback_settings, http_client)
            return await client.complete(
                registry.get("general"),
                [{"role": "user", "content": "Hello"}],
            )

    with pytest.raises(MissingApiKeyError):
        asyncio.run(run_request())
