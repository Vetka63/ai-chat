"""День 17: модель вызывает разрешённый MCP-инструмент и сохраняет след."""

import json

import httpx
import pytest
from fastapi.testclient import TestClient

from application.main import create_app
from application.settings import Settings
from capabilities.mcp_discovery.models import McpDiscoveryResult, McpServerSummary, McpToolExecution, McpToolSummary
from capabilities.mcp_discovery.service import McpDiscoveryService
from infrastructure.tool_completions import ChatCompletionsToolClient


@pytest.mark.asyncio
@pytest.mark.parametrize("provider", ["deepseek", "mistral"])
async def test_tool_client_parses_provider_function_call(provider):
    captured = {}

    def respond(request):
        captured.update(json.loads(request.content))
        return httpx.Response(200, json={
            "model": "deepseek-v4-flash", "choices": [{"finish_reason": "tool_calls", "message": {
                "content": None,
                "tool_calls": [{"id": "call_1", "function": {"name": "games_mock__search_games", "arguments": '{"query":"космос"}'}}],
            }}],
            "usage": {"prompt_tokens": 12, "completion_tokens": 4, "total_tokens": 16},
        })

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
        client = ChatCompletionsToolClient(http, "test-key", provider, "https://example.test")
        answer = await client.complete_with_tools([{"role": "user", "content": "Игры про космос"}],
            [{"type": "function", "function": {"name": "games_mock__search_games", "parameters": {"type": "object"}}}],
            model="deepseek-v4-flash", temperature=0.2, max_tokens=None, tool_choice="auto")

    assert captured["tool_choice"] == "auto"
    assert ("thinking" in captured) == (provider == "deepseek")
    assert captured["tools"][0]["function"]["name"] == "games_mock__search_games"
    assert answer.tool_calls[0].arguments == {"query": "космос"}
    assert answer.usage.total_tokens == 16


def test_demo_mcp_chat_executes_tool_and_restores_trace(tmp_path, monkeypatch):
    async def discover(self, server_id):
        assert server_id == "games-mock"
        return McpDiscoveryResult(
            server=McpServerSummary(id=server_id, name="Python MCP", description="Тест",
                                    transport="streamable_http", chat_enabled=True),
            server_name="Games Catalog", protocol_version="2025-11-25",
            tools=[McpToolSummary(name="search_games", title="search_games",
                                  description="Найти игры", input_schema={"type": "object", "properties": {
                                      "query": {"type": "string"}}, "required": ["query"]})],
        )

    async def tool_result(self, server_id, tool_name, arguments):
        assert server_id == "games-mock"
        assert tool_name == "search_games"
        assert arguments == {"query": "космос"}
        return McpToolExecution(server_id=server_id, tool_name=tool_name, structured_content={
            "query": "космос", "games": [{"title": "Звёздные тропы", "description": "Экспедиция в космос."}],
        })

    monkeypatch.setattr(McpDiscoveryService, "discover", discover)
    monkeypatch.setattr(McpDiscoveryService, "call_tool", tool_result)
    settings = Settings(mode="demo", python_mcp_url="http://python.test/mcp",
                        database_path=tmp_path / "day17.sqlite3", _env_file=None)
    with TestClient(create_app(settings)) as api:
        servers = api.get("/api/v1/mcp/servers").json()
        assert any(server["id"] == "games-mock" and server["chat_enabled"] for server in servers)
        created = api.post("/api/v1/agents/mcp_games/conversations", json={
            "title": "Каталог", "mcp_server_ids": ["games-mock"],
        })
        assert created.status_code == 201, created.text
        conversation_id = created.json()["id"]
        run = api.post("/api/v1/agents/mcp_games/runs", json={
            "conversation_id": conversation_id, "message": "Какие есть игры про космос?",
        })
        assert run.status_code == 200, run.text
        assert "Звёздные тропы" in run.json()["reply"]
        assert run.json()["tool_events"][0]["tool_name"] == "search_games"
        assert len(run.json()["additional_runs"]) == 1
        assert api.post("/api/v1/agents/dialogue/conversations", json={
            "title": "Нельзя", "mcp_server_ids": ["games-mock"],
        }).status_code == 422
        assert api.post("/api/v1/agents/mcp_games/conversations", json={
            "title": "Нельзя", "mcp_server_ids": ["local-demo"],
        }).status_code == 422

    with TestClient(create_app(settings)) as restarted:
        restored = restarted.get(f"/api/v1/agents/mcp_games/conversations/{conversation_id}")
        assert restored.status_code == 200
        data = restored.json()
        assert data["mcp_server_ids"] == ["games-mock"]
        assert [message["role"] for message in data["messages"]] == ["user", "assistant"]
        assert data["tool_events"][0]["result"]["games"][0]["title"] == "Звёздные тропы"
        assert len(data["runs"]) == 2


def test_mcp_servers_can_change_or_be_disabled_per_message(tmp_path, monkeypatch):
    discoveries = []
    calls = []

    async def discover(self, server_id):
        discoveries.append(server_id)
        return McpDiscoveryResult(
            server=McpServerSummary(id=server_id, name=server_id, description="Тест",
                                    transport="streamable_http", chat_enabled=True),
            server_name=server_id, protocol_version="2025-11-25",
            tools=[McpToolSummary(name="search_games", title="search_games",
                                  description="Найти игры", input_schema={"type": "object", "properties": {
                                      "query": {"type": "string"}}, "required": ["query"]})],
        )

    async def tool_result(self, server_id, tool_name, arguments):
        calls.append(server_id)
        return McpToolExecution(server_id=server_id, tool_name=tool_name, structured_content={
            "query": arguments["query"], "games": [{"title": "Игра", "description": "Описание"}],
        })

    monkeypatch.setattr(McpDiscoveryService, "discover", discover)
    monkeypatch.setattr(McpDiscoveryService, "call_tool", tool_result)
    settings = Settings(mode="demo", python_mcp_url="http://python.test/mcp",
                        java_mcp_url="http://java.test/mcp",
                        database_path=tmp_path / "per-message.sqlite3", _env_file=None)
    with TestClient(create_app(settings)) as api:
        created = api.post("/api/v1/agents/mcp_games/conversations", json={"title": "Каталог", "mcp_server_ids": ["games-mock"]})
        assert created.status_code == 201, created.text
        chat_id = created.json()["id"]
        empty_chat = api.post("/api/v1/agents/mcp_games/conversations", json={"title": "Без MCP"})
        assert empty_chat.status_code == 201 and empty_chat.json()["mcp_server_ids"] == []
        route = "/api/v1/agents/mcp_games/runs"
        disabled = api.post(route, json={"conversation_id": chat_id, "message": "Игры?", "mcp_server_ids": []})
        assert disabled.status_code == 200, disabled.text
        assert disabled.json()["tool_events"] == []
        assert "MCP отключён" in disabled.json()["reply"]
        assert not discoveries and not calls

        switched = api.post(route, json={"conversation_id": chat_id, "message": "Игры про космос?",
                                         "mcp_server_ids": ["java-games-mock"]})
        assert switched.status_code == 200, switched.text
        assert switched.json()["tool_events"][0]["server_id"] == "java-games-mock"
        assert discoveries == ["java-games-mock"] and calls == ["java-games-mock"]

        invalid = api.post(route, json={"conversation_id": chat_id, "message": "Не сохранять",
                                        "mcp_server_ids": ["local-demo"]})
        duplicate = api.post(route, json={"conversation_id": chat_id, "message": "Не сохранять",
                                          "mcp_server_ids": ["games-mock", "games-mock"]})
        assert invalid.status_code == duplicate.status_code == 422
        history = api.get(f"/api/v1/agents/mcp_games/conversations/{chat_id}").json()
        assert [item["mcp_server_ids"] for item in history["messages"] if item["role"] == "user"] == [[], ["java-games-mock"]]
        assert history["mcp_server_ids"] == ["games-mock"]

        other = api.post("/api/v1/agents/dialogue/conversations", json={"title": "Диалог"}).json()["id"]
        rejected = api.post("/api/v1/agents/dialogue/runs", json={"conversation_id": other, "message": "Привет",
                                                                        "mcp_server_ids": ["games-mock"]})
        assert rejected.status_code == 422

    with TestClient(create_app(settings)) as restarted:
        restored = restarted.get(f"/api/v1/agents/mcp_games/conversations/{chat_id}").json()
        assert [item["mcp_server_ids"] for item in restored["messages"] if item["role"] == "user"] == [[], ["java-games-mock"]]
