import pytest

from agent_core.models import AgentCommand, AgentError
from agents.dialogue.config import DialogueAgentConfig
from agents.dialogue.factory import build_dialogue_agent
from conftest import FakeLlm


@pytest.mark.asyncio
async def test_agent_owns_prompt_and_calls_llm_once():
    llm = FakeLlm("  Готово  ")
    agent = build_dialogue_agent(
        DialogueAgentConfig(model="deepseek-test", system_prompt="Серверная инструкция"),
        llm,
    )

    result = await agent.run(AgentCommand(message="  Привет  "))

    assert result.reply == "Готово"
    assert result.agent_id == "dialogue"
    assert len(llm.calls) == 1
    assert [(item.role, item.content) for item in llm.calls[0]["messages"]] == [
        ("system", "Серверная инструкция"),
        ("user", "Привет"),
    ]


@pytest.mark.asyncio
async def test_empty_input_stops_before_llm():
    llm = FakeLlm()
    agent = build_dialogue_agent(DialogueAgentConfig(model="test", system_prompt="prompt"), llm)

    with pytest.raises(AgentError, match="Введите сообщение"):
        await agent.run(AgentCommand(message="   "))

    assert llm.calls == []


def test_history_is_not_part_of_day_6_contract():
    with pytest.raises(ValueError):
        AgentCommand.model_validate({"message": "Привет", "history": []})

