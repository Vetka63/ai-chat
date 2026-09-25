"""День 19-v2: агент вызывает три MCP-tools по порядку и не пропускает этапы."""

from fastapi.testclient import TestClient

from agent_core.models import ToolCall, ToolCompletion
from application.main import create_app
from application.settings import Settings
from capabilities.mcp_discovery.models import McpDiscoveryResult, McpServerSummary, McpToolExecution, McpToolSummary
from capabilities.mcp_discovery.service import McpDiscoveryService
from infrastructure.tool_completions import ToolProviderRouter


SEARCH = {"query": "космос", "games": [
    {"title": "Звёздные тропы", "description": "Экспедиция в космос."},
]}
SUMMARY = {"query": "космос", "game_count": 1, "markdown": "# Игры про космос\n"}


def test_agent_calls_three_tools_in_one_server_and_passes_exact_output(tmp_path, monkeypatch):
    llm_calls = []
    tool_calls = []

    async def discover(self, server_id):
        assert server_id == "game-reports"
        return McpDiscoveryResult(
            server=McpServerSummary(id=server_id, name="Отчёты", description="Тест",
                                    transport="streamable_http", chat_enabled=True,
                                    agent_ids=["game_reports"]),
            server_name="Game Report Tools", protocol_version="2025-11-25",
            tools=[
                McpToolSummary(name=name, title=name, description=name,
                               input_schema={"type": "object", "properties": {
                                   field: {"type": "string"}}, "required": [field]})
                for name, field in (("search_games", "query"),
                                    ("summarize_games", "search_result"),
                                    ("save_report", "summary_result"))
            ],
        )

    async def complete(self, messages, tools, *, model, temperature, max_tokens, tool_choice):
        llm_calls.append((tool_choice, [tool["function"]["name"] for tool in tools]))
        if tool_choice == "required":
            return ToolCompletion(
                content="", model=model, source="demo", finish_reason="tool_calls",
                tool_calls=[ToolCall(id="report-search-1", name="game_reports__search_games",
                                     arguments={"query": "космос"})],
            )
        if messages[-1]["role"] == "tool":
            return ToolCompletion(content="Отчёт сохранён.", model=model, source="demo")
        return ToolCompletion(content="Космос — частая тема игр.", model=model, source="demo")

    async def call_tool(self, server_id, tool_name, arguments):
        assert server_id == "game-reports"
        tool_calls.append((tool_name, arguments))
        if tool_name == "search_games":
            return McpToolExecution(server_id=server_id, tool_name=tool_name,
                                    structured_content=SEARCH)
        if tool_name == "summarize_games":
            assert arguments == {"search_result": SEARCH}
            return McpToolExecution(server_id=server_id, tool_name=tool_name,
                                    structured_content=SUMMARY)
        assert tool_name == "save_report"
        assert arguments["summary_result"] == SUMMARY
        assert arguments["operation_id"]
        return McpToolExecution(server_id=server_id, tool_name=tool_name,
                                structured_content={
                                    "status": "saved", "report_id": arguments["operation_id"],
                                    "file_name": "report.md", "query": "космос",
                                    "game_count": 1, "report_markdown": SUMMARY["markdown"],
                                })

    monkeypatch.setattr(McpDiscoveryService, "discover", discover)
    monkeypatch.setattr(McpDiscoveryService, "call_tool", call_tool)
    monkeypatch.setattr(ToolProviderRouter, "complete_with_tools", complete)
    settings = Settings(mode="demo", game_report_mcp_url="http://reports.test/mcp",
                        python_mcp_url="http://games.test/mcp",
                        database_path=tmp_path / "day19.sqlite3", _env_file=None)
    with TestClient(create_app(settings)) as api:
        agents = api.get("/api/v1/agents").json()["agents"]
        assert any(item["id"] == "game_reports" for item in agents)
        created = api.post("/api/v1/agents/game_reports/conversations",
                           json={"title": "Отчёты", "mcp_server_ids": ["game-reports"]})
        assert created.status_code == 201, created.text
        chat_id = created.json()["id"]
        ordinary = api.post("/api/v1/agents/game_reports/runs",
                            json={"conversation_id": chat_id, "message": "Что такое RPG?"})
        report = api.post("/api/v1/agents/game_reports/runs",
                          json={"conversation_id": chat_id,
                                "message": "Создай и сохрани отчёт об играх про космос"})
        no_mcp = api.post("/api/v1/agents/game_reports/runs",
                          json={"conversation_id": chat_id, "message": "Создай отчёт про сад",
                                "mcp_server_ids": []})
        history = api.get(f"/api/v1/agents/game_reports/conversations/{chat_id}").json()
        wrong_agent = api.post("/api/v1/agents/mcp_games/conversations",
                               json={"title": "Нельзя", "mcp_server_ids": ["game-reports"]})
        wrong_server = api.post("/api/v1/agents/game_reports/conversations",
                                json={"title": "Нельзя", "mcp_server_ids": ["games-mock"]})

    assert ordinary.status_code == report.status_code == no_mcp.status_code == 200
    assert ordinary.json()["tool_events"] == no_mcp.json()["tool_events"] == []
    assert no_mcp.json()["source"] == "policy"
    assert "не сохранял файл" in no_mcp.json()["reply"]
    assert [event["tool_name"] for event in report.json()["tool_events"]] == [
        "search_games", "summarize_games", "save_report",
    ]
    assert [event["tool_name"] for event in history["tool_events"]] == [
        "search_games", "summarize_games", "save_report",
    ]
    assert [name for name, _ in tool_calls] == ["search_games", "summarize_games", "save_report"]
    assert llm_calls == [
        ("none", []),
        ("required", ["game_reports__search_games"]),
        ("none", []),
    ]
    assert wrong_agent.status_code == wrong_server.status_code == 422


def test_invalid_search_result_stops_before_summarize_and_save(tmp_path, monkeypatch):
    calls = []

    async def discover(self, server_id):
        return McpDiscoveryResult(
            server=McpServerSummary(id=server_id, name="Отчёты", description="Тест",
                                    transport="streamable_http", chat_enabled=True,
                                    agent_ids=["game_reports"]),
            server_name="Game Report Tools", protocol_version="2025-11-25",
            tools=[McpToolSummary(name=name, title=name, description=name,
                                  input_schema={"type": "object", "properties": {}})
                   for name in ("search_games", "summarize_games", "save_report")],
        )

    async def complete(self, messages, tools, *, model, temperature, max_tokens, tool_choice):
        return ToolCompletion(content="", model=model, source="demo", finish_reason="tool_calls",
                              tool_calls=[ToolCall(id="search", name="game_reports__search_games",
                                                   arguments={"query": "космос"})])

    async def call_tool(self, server_id, tool_name, arguments):
        calls.append(tool_name)
        return McpToolExecution(server_id=server_id, tool_name=tool_name,
                                structured_content={"query": "космос", "games": "not-a-list"})

    monkeypatch.setattr(McpDiscoveryService, "discover", discover)
    monkeypatch.setattr(McpDiscoveryService, "call_tool", call_tool)
    monkeypatch.setattr(ToolProviderRouter, "complete_with_tools", complete)
    settings = Settings(mode="demo", game_report_mcp_url="http://reports.test/mcp",
                        database_path=tmp_path / "day19-error.sqlite3", _env_file=None)
    with TestClient(create_app(settings)) as api:
        created = api.post("/api/v1/agents/game_reports/conversations",
                           json={"title": "Отчёты", "mcp_server_ids": ["game-reports"]})
        response = api.post("/api/v1/agents/game_reports/runs",
                            json={"conversation_id": created.json()["id"],
                                  "message": "Создай отчёт про космос"})
    assert response.status_code == 502
    assert calls == ["search_games"]
