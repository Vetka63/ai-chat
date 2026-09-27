"""День 16: реальное stdio-соединение и HTTP-флоу обнаружения инструментов."""

import pytest
from fastapi.testclient import TestClient

from agent_core.registry import AgentRegistry
from application.main import create_app
from application.settings import Settings
from capabilities.mcp_discovery.models import McpServerSummary
from capabilities.mcp_discovery.service import McpDiscoveryError, McpDiscoveryService, McpServerDefinition, default_servers
from infrastructure.sqlite_store import SqliteConversationStore


@pytest.mark.asyncio
async def test_local_mcp_connection_returns_real_tools():
    result = await McpDiscoveryService().discover("local-demo")

    assert result.server.id == "local-demo"
    assert result.protocol_version
    assert {tool.name for tool in result.tools} == {"add_numbers", "echo_text"}
    assert result.tools[0].input_schema["type"] == "object"


@pytest.mark.asyncio
async def test_server_command_cannot_be_supplied_by_client():
    service = McpDiscoveryService()
    assert service.list_servers()[0].model_dump().keys() == {
        "id", "name", "description", "transport", "chat_enabled", "agent_ids",
    }

    with pytest.raises(McpDiscoveryError) as error:
        await service.discover("not-allowed")
    assert error.value.code == "mcp_server_not_found"
    assert error.value.status == 404


def test_both_games_servers_are_separate_http_options():
    servers = default_servers("http://python-mcp-games:8080/mcp", "http://java-mcp-games:8080/mcp")
    summaries = {item.summary.id: item.summary for item in servers}

    assert summaries["games-mock"].transport == "streamable_http"
    assert next(item for item in servers if item.summary.id == "games-mock").url == "http://python-mcp-games:8080/mcp"
    assert next(item for item in servers if item.summary.id == "games-mock").command is None
    assert summaries["java-games-mock"].transport == "streamable_http"
    assert summaries["java-games-mock"].chat_enabled
    assert next(item for item in servers if item.summary.id == "java-games-mock").url == "http://java-mcp-games:8080/mcp"
    assert next(item for item in servers if item.summary.id == "java-games-mock").command is None


def test_game_report_server_available_to_both_agents():
    servers = default_servers(game_report_mcp_url="http://python-mcp-game-reports:8080/mcp")
    report = next(item.summary for item in servers if item.summary.id == "game-reports")

    assert report.chat_enabled
    assert report.agent_ids == ["game_reports", "game_orchestrator"]
    assert "композиция" in report.description


@pytest.mark.asyncio
async def test_connection_failure_is_safe_for_ui():
    service = McpDiscoveryService((
        McpServerDefinition(
            McpServerSummary(id="broken", name="Broken", description="Test"),
            command="missing-mcp-test-executable-16",
            args=(),
        ),
    ))
    with pytest.raises(McpDiscoveryError) as error:
        await service.discover("broken")
    assert error.value.code == "mcp_connection_failed"
    assert "missing-mcp-test-executable-16" not in error.value.message


def test_mcp_api_lists_servers_and_discovers_tools(tmp_path):
    store = SqliteConversationStore(tmp_path / "mcp.sqlite3")
    settings = Settings(mode="demo", database_path=tmp_path / "mcp.sqlite3", _env_file=None)
    with TestClient(create_app(settings, AgentRegistry([]), store)) as api:
        catalog = api.get("/api/v1/mcp/servers")
        discovery = api.post("/api/v1/mcp/servers/local-demo/discover")
        missing = api.post("/api/v1/mcp/servers/unknown/discover")

    assert catalog.status_code == 200
    assert catalog.json()[0]["id"] == "local-demo"
    assert "command" not in catalog.json()[0]
    assert discovery.status_code == 200
    assert {tool["name"] for tool in discovery.json()["tools"]} == {"add_numbers", "echo_text"}
    assert missing.status_code == 404
    assert missing.json()["code"] == "mcp_server_not_found"
