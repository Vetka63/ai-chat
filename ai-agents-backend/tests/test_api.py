from fastapi.testclient import TestClient

from agent_core.registry import AgentRegistry
from agents.dialogue.config import DialogueAgentConfig
from agents.dialogue.factory import build_dialogue_agent
from application.main import create_app
from application.settings import Settings
from conftest import FakeLlm


def client():
    agent = build_dialogue_agent(
        DialogueAgentConfig(model="deepseek-test", system_prompt="Системный промпт"),
        FakeLlm("Ответ из агента"),
    )
    return TestClient(create_app(Settings(mode="demo"), AgentRegistry([agent])))


def test_list_and_run_agent():
    with client() as api:
        listed = api.get("/api/v1/agents")
        result = api.post("/api/v1/agents/dialogue/runs", json={"message": "Вопрос"})

    assert listed.status_code == 200
    assert listed.json()["agents"][0]["id"] == "dialogue"
    assert result.status_code == 200
    assert result.json()["reply"] == "Ответ из агента"


def test_unknown_agent_and_history_are_rejected():
    with client() as api:
        missing = api.post("/api/v1/agents/missing/runs", json={"message": "Вопрос"})
        history = api.post(
            "/api/v1/agents/dialogue/runs",
            json={"message": "Вопрос", "history": [{"role": "user", "content": "Раньше"}]},
        )

    assert missing.status_code == 404
    assert missing.json()["code"] == "agent_not_found"
    assert history.status_code == 422
    assert history.json()["code"] == "invalid_request"

