"""Контракт игрового Python MCP-сервера, независимый от кода агента."""

import httpx
import pytest
from mcp import Client

from python_mcp_games.server import create_server


@pytest.mark.asyncio
async def test_search_games_declares_schema_and_returns_mock_api_data():
    seen = []

    def respond(request):
        seen.append(str(request.url))
        return httpx.Response(200, json={"query": "космос", "games": [
            {"title": "Звёздные тропы", "description": "Экспедиция в космос."},
        ]})

    async with httpx.AsyncClient(base_url="http://mock.test", transport=httpx.MockTransport(respond)) as http:
        async with Client(create_server("http://mock.test", http)) as client:
            tools = (await client.list_tools()).tools
            assert [tool.name for tool in tools] == ["search_games"]
            assert "query" in tools[0].input_schema["properties"]
            result = await client.call_tool("search_games", {"query": "космос"})

    assert seen == ["http://mock.test/games?query=%D0%BA%D0%BE%D1%81%D0%BC%D0%BE%D1%81"]
    assert result.structured_content["games"][0]["title"] == "Звёздные тропы"


@pytest.mark.asyncio
async def test_search_games_rejects_empty_query():
    async with Client(create_server("http://mock.test")) as client:
        result = await client.call_tool("search_games", {"query": " "})
    assert result.is_error
