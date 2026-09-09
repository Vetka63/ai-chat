from fastapi.testclient import TestClient

from agent_core.registry import AgentRegistry
from agents.dialogue.config import DialogueAgentConfig
from agents.dialogue.factory import build_dialogue_agent
from application.main import create_app
from application.settings import Settings
from conftest import FakeLlm
from infrastructure.sqlite_store import SqliteConversationStore


def client(tmp_path):
    store = SqliteConversationStore(tmp_path / "api.sqlite3")
    agent = build_dialogue_agent(
        DialogueAgentConfig(model="deepseek-test", system_prompt="Системный промпт"),
        FakeLlm("Ответ из агента"),
        store,
    )
    settings = Settings(mode="demo", database_path=tmp_path / "api.sqlite3", _env_file=None)
    return TestClient(create_app(settings, AgentRegistry([agent]), store))


def test_list_create_run_and_restore_conversation(tmp_path):
    with client(tmp_path) as api:
        listed = api.get("/api/v1/agents")
        created = api.post(
            "/api/v1/agents/dialogue/conversations",
            json={"title": "Новый чат"},
        ).json()
        result = api.post(
            "/api/v1/agents/dialogue/runs",
            json={"conversation_id": created["id"], "message": "Вопрос"},
        )
        detail = api.get(f"/api/v1/agents/dialogue/conversations/{created['id']}")

    assert listed.status_code == 200
    assert listed.json()["agents"][0]["id"] == "dialogue"
    assert result.status_code == 200
    assert result.json()["reply"] == "Ответ из агента"
    assert [message["role"] for message in detail.json()["messages"]] == ["user", "assistant"]
    assert detail.json()["title"] == "Вопрос"


def test_unknown_agent_and_client_history_are_rejected(tmp_path):
    with client(tmp_path) as api:
        missing = api.post(
            "/api/v1/agents/missing/runs",
            json={"conversation_id": "chat", "message": "Вопрос"},
        )
        history = api.post(
            "/api/v1/agents/dialogue/runs",
            json={
                "conversation_id": "chat",
                "message": "Вопрос",
                "history": [{"role": "user", "content": "Раньше"}],
            },
        )

    assert missing.status_code == 404
    assert missing.json()["code"] == "agent_not_found"
    assert history.status_code == 422
    assert history.json()["code"] == "invalid_request"


def test_delete_conversation(tmp_path):
    with client(tmp_path) as api:
        created = api.post("/api/v1/agents/dialogue/conversations", json={}).json()
        deleted = api.delete(f"/api/v1/agents/dialogue/conversations/{created['id']}")
        missing = api.get(f"/api/v1/agents/dialogue/conversations/{created['id']}")

    assert deleted.status_code == 204
    assert missing.status_code == 404

