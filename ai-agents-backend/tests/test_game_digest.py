"""День 18: отдельный агент и HTTP-флоу управления MCP-подпиской."""

from fastapi.testclient import TestClient

from agents.game_digest.agent import GameCatalogGateway, GameDigestAgent, GameFeedGateway
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
                        java_mcp_url="http://catalog.test/mcp",
                        database_path=tmp_path / "agents.sqlite3", _env_file=None)
    with TestClient(create_app(settings)) as api:
        agents = api.get("/api/v1/agents").json()["agents"]
        assert any(item["id"] == "game_digest" and "scheduled_reports" in item["capabilities"] for item in agents)
        created = api.post("/api/v1/agents/game_digest/conversations", json={"title": "Лента"})
        assert created.status_code == 201, created.text
        conversation_id = created.json()["id"]
        base = f"/api/v1/agents/game_digest/conversations/{conversation_id}"
        assert api.put(base + "/schedule", json={"interval_seconds": 30}).status_code == 422
        assert api.put(base + "/schedule", json={"interval_seconds": 1800}).status_code == 200
        assert api.get(base + "/reports").json()["reports"][0]["text"] == "Новая игра"
        assert api.post("/api/v1/agents/game_digest/runs", json={"conversation_id": conversation_id, "message": "Привет"}).status_code == 422
        assert api.delete(base).status_code == 204
    assert calls[0] == ("create_watch", {"conversation_id": conversation_id, "interval_seconds": 1800})
    assert calls[-1] == ("delete_watch", {"conversation_id": conversation_id})


def test_digest_is_disabled_without_server(tmp_path):
    settings = Settings(mode="demo", game_feed_mcp_url="", database_path=tmp_path / "agents.sqlite3", _env_file=None)
    with TestClient(create_app(settings)) as api:
        assert all(item["id"] != "game_digest" for item in api.get("/api/v1/agents").json()["agents"])


async def test_scheduled_agent_uses_java_mcp_then_saves_only_ten_games():
    calls = []

    class Store:
        async def get(self, agent_id, conversation_id):
            assert (agent_id, conversation_id) == ("game_digest", "chat-1")

    class Feed:
        async def call(self, name, arguments):
            calls.append((name, arguments))
            if name == "save_digest":
                return {"report": {"id": "report-1", "stats": {"new_games": len(arguments["games"])}}}
            return {"watches": []}

    class Catalog:
        async def new_games(self, after_id):
            calls.append(("get_new_games", after_id))
            return {"latest_id": 22, "games": [
                {"id": value, "title": f"Игра {value}", "genre": "Стратегия",
                 "description": "Описание", "created_at": "2026-09-25T00:00:00Z"}
                for value in range(22, 12, -1)
            ]}

    agent = GameDigestAgent(Store(), Feed(), Catalog())
    report = await agent.run_scheduled({"id": "watch-1", "conversation_id": "chat-1",
                                        "scheduled_for": "2026-09-25T12:00:00Z",
                                        "last_game_id": 7})
    assert report["stats"]["new_games"] == 10
    assert calls[0] == ("get_new_games", 7)
    assert calls[1][0] == "save_digest"
    assert calls[1][1]["latest_id"] == 22
    assert "Игра 22" in calls[1][1]["text"]
