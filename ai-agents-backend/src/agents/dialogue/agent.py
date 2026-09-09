"""Инкапсулированный сценарий универсального диалогового агента."""

from agent_core.contracts import InputPolicy, InputValidator, LlmClient, OutputPolicy, OutputValidator
from agent_core.models import AgentCommand, AgentInfo, AgentResult, Message
from agents.dialogue.config import DialogueAgentConfig


class DialogueAgent:
    """Сам строит запрос к LLM и применяет собственные политики и валидаторы."""

    def __init__(
        self,
        config: DialogueAgentConfig,
        llm: LlmClient,
        input_policy: InputPolicy,
        input_validators: list[InputValidator],
        output_policy: OutputPolicy,
        output_validators: list[OutputValidator],
    ):
        self.config = config
        self.llm = llm
        self.input_policy = input_policy
        self.input_validators = input_validators
        self.output_policy = output_policy
        self.output_validators = output_validators

    @property
    def info(self) -> AgentInfo:
        return AgentInfo(id=self.config.id, name=self.config.name, description=self.config.description)

    async def run(self, command: AgentCommand) -> AgentResult:
        text = self.input_policy.prepare(command.message)
        for validator in self.input_validators:
            validator.validate(text)

        completion = await self.llm.complete(
            [
                Message(role="system", content=self.config.system_prompt),
                Message(role="user", content=text),
            ],
            model=self.config.model,
            temperature=self.config.temperature,
            max_tokens=self.config.max_tokens,
        )

        reply = self.output_policy.present(completion.content)
        for validator in self.output_validators:
            validator.validate(reply)

        return AgentResult(
            agent_id=self.config.id,
            reply=reply,
            model=completion.model,
            source=completion.source,
        )

