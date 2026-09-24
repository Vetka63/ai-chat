"""День 17: модель вызывает разрешённый MCP-инструмент и сохраняет след."""

import json

import httpx
import pytest
from fastapi.testclient import TestClient

from agent_core.models import ToolCall, ToolCompletion
from application.main import create_app
from application.settings import Settings
from capabilities.mcp_discovery.models import McpDiscoveryResult, McpServerSummary, McpToolExecution, McpToolSummary
from capabilities.mcp_discovery.service import McpDiscoveryService
from infrastructure.tool_completions import ChatCompletionsToolClient, DemoToolClient, ToolProviderRouter


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


@pytest.mark.asyncio
async def test_demo_client_treats_enabled_tool_as_optional():
    client = DemoToolClient()
    tools = [{"type": "function", "function": {"name": "games_mock__search_games"}}]
    general = await client.complete_with_tools([{"role": "user", "content": "Что такое RPG?"}], tools,
                                                model="deepseek-v4-flash", temperature=0.2,
                                                max_tokens=None, tool_choice="auto")
    catalog = await client.complete_with_tools(
        [{"role": "user", "content": "Найди в нашем каталоге игры про космос"}], tools,
        model="deepseek-v4-flash", temperature=0.2, max_tokens=None, tool_choice="auto")
    unknown = await client.complete_with_tools(
        [{"role": "user", "content": "А слышал что-то про игру Механика дождя?"}], tools,
        model="deepseek-v4-flash", temperature=0.2, max_tokens=None, tool_choice="auto")
    forced = await client.complete_with_tools(
        [{"role": "user", "content": "А слышал что-то про игру Механика дождя?"}], tools,
        model="deepseek-v4-flash", temperature=0.2, max_tokens=None, tool_choice="required")

    assert "ролевая игра" in general.content
    assert general.tool_calls == []
    assert catalog.tool_calls[0].name == "games_mock__search_games"
    assert "нет надёжных сведений" in unknown.content
    assert unknown.tool_calls == []
    assert forced.tool_calls[0].arguments == {"query": "Механика дождя"}


def test_mcp_agent_offers_tool_but_does_not_call_it_for_general_question(tmp_path, monkeypatch):
    captured = {}

    async def discover(self, server_id):
        return McpDiscoveryResult(
            server=McpServerSummary(id=server_id, name="Python MCP", description="Тест",
                                    transport="streamable_http", chat_enabled=True),
            server_name="Games Catalog", protocol_version="2025-11-25",
            tools=[McpToolSummary(name="search_games", title="search_games",
                                  description="Ищи в каталоге игру, о которой нет надёжных сведений.",
                                  input_schema={"type": "object", "properties": {
                                      "query": {"type": "string"}}, "required": ["query"]})],
        )

    async def complete(self, messages, tools, *, model, temperature, max_tokens, tool_choice):
        captured.update(messages=messages, tools=tools, tool_choice=tool_choice)
        return ToolCompletion(content="RPG — жанр компьютерных игр.", model=model,
                              source="demo", finish_reason="stop")

    async def unexpected_tool_call(self, server_id, tool_name, arguments):
        raise AssertionError("MCP tools/call не должен выполняться без запроса модели")

    monkeypatch.setattr(McpDiscoveryService, "discover", discover)
    monkeypatch.setattr(McpDiscoveryService, "call_tool", unexpected_tool_call)
    monkeypatch.setattr(ToolProviderRouter, "complete_with_tools", complete)
    settings = Settings(mode="demo", python_mcp_url="http://python.test/mcp",
                        database_path=tmp_path / "selective-tool.sqlite3", _env_file=None)
    with TestClient(create_app(settings)) as api:
        created = api.post("/api/v1/agents/mcp_games/conversations", json={
            "title": "Обычный вопрос", "mcp_server_ids": ["games-mock"],
        })
        run = api.post("/api/v1/agents/mcp_games/runs", json={
            "conversation_id": created.json()["id"], "message": "Что такое RPG?",
        })

    assert run.status_code == 200, run.text
    assert run.json()["reply"] == "RPG — жанр компьютерных игр."
    assert run.json()["tool_events"] == []
    assert captured["tool_choice"] == "auto"
    assert captured["tools"][0]["function"]["name"] == "games_mock__search_games"
    assert "о которой у тебя нет надёжных сведений" in captured["messages"][0]["content"]
    assert "не жди, пока пользователь произнесёт слово «каталог»" in captured["messages"][0]["content"].casefold()
    assert "нет надёжных сведений" in captured["tools"][0]["function"]["description"]


def test_mcp_agent_without_tools_still_uses_llm_for_general_question(tmp_path, monkeypatch):
    captured = {}

    async def complete(self, messages, tools, *, model, temperature, max_tokens, tool_choice):
        captured.update(messages=messages, tools=tools, tool_choice=tool_choice)
        return ToolCompletion(content="RPG — жанр ролевых игр.", model=model,
                              source="demo", finish_reason="stop")

    monkeypatch.setattr(ToolProviderRouter, "complete_with_tools", complete)
    settings = Settings(mode="demo", database_path=tmp_path / "without-tools.sqlite3", _env_file=None)
    with TestClient(create_app(settings)) as api:
        created = api.post("/api/v1/agents/mcp_games/conversations", json={"title": "Обычный чат"})
        run = api.post("/api/v1/agents/mcp_games/runs", json={
            "conversation_id": created.json()["id"], "message": "Что такое RPG?",
        })

    assert run.status_code == 200, run.text
    assert run.json()["reply"] == "RPG — жанр ролевых игр."
    assert run.json()["tool_events"] == []
    assert captured["tools"] == []
    assert captured["tool_choice"] == "none"
    assert "MCP-инструменты отключены" not in captured["messages"][0]["content"]
    assert "не предлагай их включить" in captured["messages"][0]["content"]


@pytest.mark.parametrize(("message", "draft"), [
    ("А слышал что-то про игру Механика дождя?", "Про эту игру у меня нет надёжных сведений."),
    ("Расскажи мне про Механику дождя", "«Механика дождя» — это, скорее всего, не название конкретной игры."),
    ("Найди в нашем каталоге игру Механика дождя", "Уточни, где искать."),
])
def test_agent_retries_catalog_lookup_when_model_skips_tool(tmp_path, monkeypatch, message, draft):
    calls = []

    async def discover(self, server_id):
        return McpDiscoveryResult(
            server=McpServerSummary(id=server_id, name="Java MCP", description="Тест",
                                    transport="streamable_http", chat_enabled=True),
            server_name="Games Catalog", protocol_version="2025-11-25",
            tools=[McpToolSummary(name="search_games", title="search_games",
                                  description="Поиск конкретной неизвестной игры в каталоге.",
                                  input_schema={"type": "object", "properties": {
                                      "query": {"type": "string"}}, "required": ["query"]})],
        )

    async def complete(self, messages, tools, *, model, temperature, max_tokens, tool_choice):
        calls.append((tool_choice, [tool["function"]["name"] for tool in tools]))
        if tool_choice == "auto":
            return ToolCompletion(content=draft, model=model, source="demo", finish_reason="stop")
        if tool_choice == "required":
            return ToolCompletion(content="", model=model, source="demo", finish_reason="tool_calls",
                                  tool_calls=[ToolCall(id="call-fallback", name="java_games_mock__search_games",
                                                       arguments={"query": "Механика дождя"})])
        assert messages[-1]["role"] == "tool"
        return ToolCompletion(content="В каталоге есть Механика дождя — головоломка про погоду.",
                              model=model, source="demo", finish_reason="stop")

    async def tool_result(self, server_id, tool_name, arguments):
        assert (server_id, tool_name, arguments) == ("java-games-mock", "search_games", {"query": "Механика дождя"})
        return McpToolExecution(server_id=server_id, tool_name=tool_name, structured_content={
            "query": "Механика дождя", "games": [{"title": "Механика дождя", "description": "Головоломка про погоду."}],
        })

    monkeypatch.setattr(McpDiscoveryService, "discover", discover)
    monkeypatch.setattr(McpDiscoveryService, "call_tool", tool_result)
    monkeypatch.setattr(ToolProviderRouter, "complete_with_tools", complete)
    settings = Settings(mode="demo", java_mcp_url="http://java.test/mcp",
                        database_path=tmp_path / "lookup-retry.sqlite3", _env_file=None)
    with TestClient(create_app(settings)) as api:
        created = api.post("/api/v1/agents/mcp_games/conversations", json={
            "title": "Повторный выбор", "mcp_server_ids": ["java-games-mock"],
        })
        chat_id = created.json()["id"]
        run = api.post("/api/v1/agents/mcp_games/runs", json={"conversation_id": chat_id, "message": message})
        history = api.get(f"/api/v1/agents/mcp_games/conversations/{chat_id}").json()

    assert run.status_code == 200, run.text
    assert [choice for choice, _ in calls] == ["auto", "required", "none"]
    assert calls[1][1] == ["java_games_mock__search_games"]
    assert run.json()["tool_events"][0]["arguments"] == {"query": "Механика дождя"}
    assert [item["purpose"] for item in run.json()["additional_runs"]] == ["mcp_selection", "mcp_selection_retry"]
    assert [item["content"] for item in history["messages"]] == [message, run.json()["reply"]]
    assert draft not in run.json()["reply"]


def test_demo_chat_answers_without_mcp_and_does_not_push_connection(tmp_path):
    settings = Settings(mode="demo", database_path=tmp_path / "demo-without-tools.sqlite3", _env_file=None)
    with TestClient(create_app(settings)) as api:
        created = api.post("/api/v1/agents/mcp_games/conversations", json={"title": "Без MCP"})
        route = "/api/v1/agents/mcp_games/runs"
        general = api.post(route, json={"conversation_id": created.json()["id"], "message": "Что такое RPG?"})
        catalog = api.post(route, json={"conversation_id": created.json()["id"],
                                        "message": "Найди в нашем каталоге игры про космос"})

    assert general.status_code == catalog.status_code == 200
    assert "ролевая игра" in general.json()["reply"]
    assert "MCP" not in general.json()["reply"]
    assert "не могу проверить учебный каталог" in catalog.json()["reply"]
    assert "Включите" not in catalog.json()["reply"]
    assert general.json()["tool_events"] == catalog.json()["tool_events"] == []


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
            "conversation_id": conversation_id, "message": "Найди в нашем каталоге игры про космос.",
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
        enabled_later = api.post(route, json={"conversation_id": empty_chat.json()["id"],
                                              "message": "Найди в нашем каталоге игру", "mcp_server_ids": ["games-mock"]})
        assert enabled_later.status_code == 200, enabled_later.text
        assert enabled_later.json()["tool_events"][0]["server_id"] == "games-mock"
        assert calls == ["games-mock"]
        assert api.get(f"/api/v1/agents/mcp_games/conversations/{empty_chat.json()['id']}").json()["messages"][0]["mcp_server_ids"] == ["games-mock"]
        discoveries.clear()
        calls.clear()
        disabled = api.post(route, json={"conversation_id": chat_id, "message": "Игры?", "mcp_server_ids": []})
        assert disabled.status_code == 200, disabled.text
        assert disabled.json()["tool_events"] == []
        assert "MCP" not in disabled.json()["reply"]
        assert not discoveries and not calls

        switched = api.post(route, json={"conversation_id": chat_id, "message": "Найди в нашем каталоге игры про космос.",
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
