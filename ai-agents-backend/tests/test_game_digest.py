"""День 18: отдельный агент и HTTP-флоу управления MCP-подпиской."""

from fastapi.testclient import TestClient

from agents.game_digest.agent import GameFeedGateway
from application.main import create_app
from application.settings import Settings


def test_digest_chat_schedule_reports_and_cleanup(tmp_path, monkeypatch):
    calls = []

    async def fake_call(self, name, arguments):
        calls.append((name, arguments))
        if name == "list_reports":
            return {"reports": [{"id": "r1", "text": "Новая игра", "stats": {"runs": 1}}]}
        return {"watch": {"status": "active" if name == "create_watch" else "stopped"}}

    monkeypatch.setattr(GameFeedGateway, "call", fake_call)
    settings = Settings(mode="demo", game_feed_mcp_url="http://feed.test/mcp",
                        database_path=tmp_path / "agents.sqlite3", _env_file=None)
    with TestClient(create_app(settings)) as api:
        agents = api.get("/api/v1/agents").json()["agents"]
        assert any(item["id"] == "game_digest" and "scheduled_reports" in item["capabilities"] for item in agents)
        created = api.post("/api/v1/agents/game_digest/conversations", json={"title": "Лента"})
        assert created.status_code == 201, created.text
        conversation_id = created.json()["id"]
        base = f"/api/v1/agents/game_digest/conversations/{conversation_id}"
        assert api.put(base + "/schedule", json={"interval_seconds": 30}).status_code == 422
        assert api.put(base + "/schedule", json={"interval_seconds": 60}).status_code == 200
        assert api.get(base + "/reports").json()["reports"][0]["text"] == "Новая игра"
        assert api.post("/api/v1/agents/game_digest/runs", json={"conversation_id": conversation_id, "message": "Привет"}).status_code == 422
        assert api.delete(base).status_code == 204
    assert calls[0] == ("create_watch", {"conversation_id": conversation_id, "interval_seconds": 60})
    assert calls[-1] == ("delete_watch", {"conversation_id": conversation_id})


def test_digest_is_disabled_without_server(tmp_path):
    settings = Settings(mode="demo", game_feed_mcp_url="", database_path=tmp_path / "agents.sqlite3", _env_file=None)
    with TestClient(create_app(settings)) as api:
        assert all(item["id"] != "game_digest" for item in api.get("/api/v1/agents").json()["agents"])
