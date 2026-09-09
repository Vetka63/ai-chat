import httpx
import pytest

from agent_core.models import Message
from infrastructure.deepseek import DeepSeekClient


@pytest.mark.asyncio
async def test_deepseek_payload_contains_only_current_system_and_user_messages():
    captured = {}

    async def handler(request: httpx.Request):
        captured.update(__import__("json").loads(request.content))
        return httpx.Response(200, json={"model": "returned-model", "choices": [{"message": {"content": "ok"}}]})

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler), base_url="https://api.deepseek.com/"
    ) as http:
        result = await DeepSeekClient(http, "secret").complete(
            [Message(role="system", content="prompt"), Message(role="user", content="question")],
            model="deepseek-test",
            temperature=0.7,
            max_tokens=100,
        )

    assert result.content == "ok"
    assert result.model == "returned-model"
    assert captured["messages"] == [
        {"role": "system", "content": "prompt"},
        {"role": "user", "content": "question"},
    ]

