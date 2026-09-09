import pytest

from agent_core.models import AgentCommand, AgentError
from agents.dialogue.config import DialogueAgentConfig
from agents.dialogue.factory import build_dialogue_agent
from conftest import FakeLlm
from infrastructure.sqlite_store import SqliteConversationStore


@pytest.mark.asyncio
async def test_agent_owns_prompt_and_calls_llm_once(tmp_path):
    llm = FakeLlm("  Готово  ")
    store = SqliteConversationStore(tmp_path / "agent.sqlite3")
    await store.initialize()
    conversation = await store.create("dialogue", "Тест")
    agent = build_dialogue_agent(
        DialogueAgentConfig(model="deepseek-test", system_prompt="Серверная инструкция"),
        llm,
        store,
    )

    result = await agent.run(AgentCommand(conversation_id=conversation.id, message="  Привет  "))

    assert result.reply == "Готово"
    assert result.agent_id == "dialogue"
    assert len(llm.calls) == 1
    assert [(item.role, item.content) for item in llm.calls[0]["messages"]] == [
        ("system", "Серверная инструкция"),
        ("user", "Привет"),
    ]
    saved = await store.get("dialogue", conversation.id)
    assert [(item.role, item.content) for item in saved.messages] == [
        ("user", "Привет"),
        ("assistant", "Готово"),
    ]


@pytest.mark.asyncio
async def test_empty_input_stops_before_llm(tmp_path):
    llm = FakeLlm()
    store = SqliteConversationStore(tmp_path / "agent.sqlite3")
    await store.initialize()
    agent = build_dialogue_agent(DialogueAgentConfig(model="test", system_prompt="prompt"), llm, store)

    with pytest.raises(AgentError, match="Введите сообщение"):
        await agent.run(AgentCommand(conversation_id="missing", message="   "))

    assert llm.calls == []


def test_history_cannot_be_supplied_by_client():
    with pytest.raises(ValueError):
        AgentCommand.model_validate({"conversation_id": "chat", "message": "Привет", "history": []})
