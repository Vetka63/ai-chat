"""День 20: модель выбирает шаги, а три MCP-сервера выполняют длинный сценарий."""

import json
import hashlib

from fastapi.testclient import TestClient

from agent_core.models import ToolCall, ToolCompletion
from agents.game_orchestrator.agent import GameOrchestratorAgent
from application.main import create_app
from application.settings import Settings
from capabilities.mcp_discovery.models import McpDiscoveryResult, McpServerSummary, McpToolExecution, McpToolSummary
from capabilities.mcp_discovery.service import McpDiscoveryService
from infrastructure.tool_completions import ToolProviderRouter


def test_two_collections_two_summaries_and_unrelated_car(tmp_path, monkeypatch):
    calls = []

    async def discover(self, server_id):
        names = {
            "java-games-mock": ["search_games"],
            "game-reports": ["summarize_games", "compose_report"],
            "go-report-store": ["save_report"],
        }[server_id]
        return McpDiscoveryResult(
            server=McpServerSummary(id=server_id, name=server_id, description="test",
                                    transport="streamable_http", chat_enabled=True),
            server_name=server_id, protocol_version="2025-11-25",
            tools=[McpToolSummary(name=name, title=name, description=name,
                                  input_schema={"type": "object"}) for name in names],
        )

    async def call_tool(self, server_id, tool_name, arguments):
        calls.append((server_id, tool_name, arguments))
        if tool_name == "search_games":
            query = arguments["query"]
            titles = {
                "космос": ["Звёздные тропы", "Орбитальный караван"],
                "сад": ["Сад ветров", "Сад фонарей"],
                "машина": ["Городской вираж"],
            }[query]
            result = {"query": query, "games": [{"title": title, "description": title + " описание"}
                                                 for title in titles]}
        elif tool_name == "summarize_games":
            source = arguments["search_result"]
            result = {"query": source["query"], "game_count": len(source["games"]),
                      "markdown": "# " + source["query"] + "\n" +
                      "\n".join(game["title"] for game in source["games"])}
        elif tool_name == "compose_report":
            assert len(arguments["summaries"]) == 2
            assert all("машина" not in item["markdown"].casefold() for item in arguments["summaries"])
            result = {"title": arguments["title"],
                      "markdown": "# Общий отчёт\n" +
                      "\n".join(item["markdown"] for item in arguments["summaries"]),
                      "source_summary_ids": [item["id"] for item in arguments["summaries"]]}
        else:
            assert server_id == "go-report-store" and tool_name == "save_report"
            draft = arguments["report_draft"]
            result = {"status": "saved", "report_id": arguments["operation_id"],
                      "file_name": "report.md", "title": draft["title"],
                      "summary_count": 2, "source_summary_ids": draft["source_summary_ids"],
                      "content_sha256": hashlib.sha256(draft["markdown"].encode("utf-8")).hexdigest()}
        return McpToolExecution(server_id=server_id, tool_name=tool_name, structured_content=result)

    async def complete(self, messages, tools, *, model, temperature, max_tokens, tool_choice):
        user = next(item["content"] for item in reversed(messages) if item["role"] == "user")
        workspace = json.loads(messages[1]["content"].split(": ", 1)[1])
        offered = {item["function"]["name"] for item in tools}
        last = json.loads(messages[-1]["content"]) if messages[-1]["role"] == "tool" else None
        call = None
        if last and last.get("status") == "saved":
            return ToolCompletion(content="Отчёт сохранён.", model=model, source="demo")
        if "сводку" in user and "отчёт" not in user:
            if last:
                return ToolCompletion(content="Сводка готова.", model=model, source="demo")
            title = "Космос" if "Космос" in user else "Сад"
            collection = next(item for item in workspace if item["type"] == "collection" and item["title"] == title)
            call = ("python_analysis__summarize_collection", {"collection_id": collection["id"]})
        elif "отчёт" in user:
            if last and last.get("source_summary_ids"):
                call = ("go_reports__save_report", {"draft_id": last["artifact_id"]})
            elif last:
                return ToolCompletion(content="Отчёт сохранён.", model=model, source="demo")
            else:
                ids = [item["id"] for item in workspace if item["type"] == "summary"]
                call = ("python_analysis__compose_report", {"title": "Общий отчёт",
                                                             "summary_ids": ids})
        elif "машину" in user:
            if last:
                return ToolCompletion(content="Нашёл игру про машину.", model=model, source="demo")
            call = ("java_games__search_games", {"query": "машина"})
        else:
            title = "Космос" if "космос" in user else "Сад"
            if last and last.get("games") and last.get("query"):
                call = ("workspace__save_collection", {"search_id": last["artifact_id"],
                                                       "title": title, "game_indices": [0, 1]})
            elif last:
                return ToolCompletion(content="Подборка сохранена.", model=model, source="demo")
            else:
                call = ("java_games__search_games", {"query": "космос" if title == "Космос" else "сад"})
        assert call[0] in offered
        return ToolCompletion(content="", model=model, source="demo", finish_reason="tool_calls",
                              tool_calls=[ToolCall(id="step-" + str(len(calls)), name=call[0],
                                                   arguments=call[1])])

    monkeypatch.setattr(McpDiscoveryService, "discover", discover)
    monkeypatch.setattr(McpDiscoveryService, "call_tool", call_tool)
    monkeypatch.setattr(ToolProviderRouter, "complete_with_tools", complete)
    settings = Settings(mode="demo", database_path=tmp_path / "day20.sqlite3",
                        java_mcp_url="http://java.test/mcp",
                        game_report_mcp_url="http://python.test/mcp",
                        go_report_mcp_url="http://go.test/mcp", _env_file=None)
    with TestClient(create_app(settings)) as api:
        created = api.post("/api/v1/agents/game_orchestrator/conversations", json={
            "title": "День 20", "mcp_server_ids": ["java-games-mock", "game-reports", "go-report-store"],
        })
        assert created.status_code == 201, created.text
        chat_id = created.json()["id"]
        for message in (
            "Собери 2 игры про космос", "Собери 2 игры про сад",
            "Сделай сводку по подборке Космос", "Сделай сводку по подборке Сад",
            "Найди игру про машину", "Составь и сохрани отчёт по двум сводкам",
        ):
            response = api.post("/api/v1/agents/game_orchestrator/runs",
                                json={"conversation_id": chat_id, "message": message})
            assert response.status_code == 200, response.text
        chat = api.get(f"/api/v1/agents/game_orchestrator/conversations/{chat_id}").json()
        other = api.post("/api/v1/agents/game_orchestrator/conversations", json={
            "title": "Другой чат", "mcp_server_ids": [],
        })
        assert other.status_code == 201, other.text
        isolated = api.get(f"/api/v1/agents/game_orchestrator/conversations/{other.json()['id']}").json()
        assert isolated["artifacts"] == []
    artifacts = chat["artifacts"]
    assert [item["kind"] for item in artifacts].count("collection") == 2
    assert [item["kind"] for item in artifacts].count("summary") == 2
    assert [item["kind"] for item in artifacts].count("report") == 1
    report = next(item for item in artifacts if item["kind"] == "report")
    assert len(report["source_ids"]) == 1
    assert "Городской вираж" not in report["payload"]["report_markdown"]
    assert [server for server, _, _ in calls][-2:] == ["game-reports", "go-report-store"]
    assert {server for server, _, _ in calls} == {"java-games-mock", "game-reports", "go-report-store"}


def test_save_collection_does_not_mean_save_report():
    agent = GameOrchestratorAgent
    assert agent._save_verb.search("Сохрани подборку «Космос»")
    assert agent._report_noun.search("Сохрани подборку «Космос»") is None
    assert agent._save_verb.search("Сохрани итоговый отчёт")
    assert agent._report_noun.search("Сохрани итоговый отчёт")
