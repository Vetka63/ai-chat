import pytest

from agent_core.models import AgentCommand, AgentError
from agents.dialogue.config import DialogueAgentConfig
from agents.dialogue.factory import build_dialogue_agent
from conftest import FakeLlm
from infrastructure.sqlite_store import SqliteConversationStore


@pytest.mark.asyncio
async def test_history_is_loaded_after_store_and_agent_restart(tmp_path):
    path = tmp_path / "persistent.sqlite3"
    first_store = SqliteConversationStore(path)
    await first_store.initialize()
    conversation = await first_store.create("dialogue", "Новый чат")
    first_llm = FakeLlm("Запомнил: северное сияние")
    first_agent = build_dialogue_agent(
        DialogueAgentConfig(model="test", system_prompt="prompt"), first_llm, first_store
    )
    await first_agent.run(
        AgentCommand(conversation_id=conversation.id, message="Кодовое слово — северное сияние")
    )

    second_store = SqliteConversationStore(path)
    await second_store.initialize()
    second_llm = FakeLlm("Северное сияние")
    second_agent = build_dialogue_agent(
        DialogueAgentConfig(model="test", system_prompt="prompt"), second_llm, second_store
    )
    await second_agent.run(
        AgentCommand(conversation_id=conversation.id, message="Какое кодовое слово?")
    )

    assert [(item.role, item.content) for item in second_llm.calls[0]["messages"]] == [
        ("system", "prompt"),
        ("user", "Кодовое слово — северное сияние"),
        ("assistant", "Запомнил: северное сияние"),
        ("user", "Какое кодовое слово?"),
    ]


@pytest.mark.asyncio
async def test_invalid_output_keeps_user_message_without_assistant_message(tmp_path):
    store = SqliteConversationStore(tmp_path / "safe.sqlite3")
    await store.initialize()
    conversation = await store.create("dialogue", "Новый чат")
    agent = build_dialogue_agent(
        DialogueAgentConfig(model="test", system_prompt="prompt"), FakeLlm("   "), store
    )

    with pytest.raises(AgentError, match="пустой ответ"):
        await agent.run(AgentCommand(conversation_id=conversation.id, message="Запрос"))

    saved = await store.get("dialogue", conversation.id)
    assert [(item.role, item.content) for item in saved.messages] == [("user", "Запрос")]


@pytest.mark.asyncio
async def test_conversations_are_isolated_by_agent(tmp_path):
    store = SqliteConversationStore(tmp_path / "isolated.sqlite3")
    await store.initialize()
    conversation = await store.create("dialogue", "Секретный чат")

    with pytest.raises(AgentError) as error:
        await store.get("another-agent", conversation.id)

    assert error.value.status == 404

