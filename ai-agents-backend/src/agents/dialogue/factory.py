"""Единственное место сборки зависимостей диалогового агента."""

from agent_core.contracts import LlmClient
from agents.dialogue.agent import DialogueAgent
from agents.dialogue.config import DialogueAgentConfig
from agents.dialogue.input_policy import TrimInputPolicy
from agents.dialogue.output_policy import PlainTextOutputPolicy
from agents.dialogue.validators import InputSizeValidator, NonEmptyInputValidator, NonEmptyOutputValidator


def build_dialogue_agent(config: DialogueAgentConfig, llm: LlmClient) -> DialogueAgent:
    """Создаёт полностью настроенный агент без участия HTTP-контроллера."""

    return DialogueAgent(
        config=config,
        llm=llm,
        input_policy=TrimInputPolicy(),
        input_validators=[NonEmptyInputValidator(), InputSizeValidator(config.max_input_chars)],
        output_policy=PlainTextOutputPolicy(),
        output_validators=[NonEmptyOutputValidator()],
    )

