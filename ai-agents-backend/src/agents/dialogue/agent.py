"""Инкапсулированный сценарий диалогового агента с постоянным контекстом."""

import asyncio

from agent_core.contracts import (
    ContextPolicy,
    ConversationStore,
    InputPolicy,
    InputValidator,
    LlmClient,
    OutputPolicy,
    OutputValidator,
)
from agent_core.models import AgentCommand, AgentInfo, AgentResult
from agents.dialogue.config import DialogueAgentConfig


class DialogueAgent:
    """Сам строит запрос к LLM и применяет собственные политики и валидаторы."""

    def __init__(
        self,
        config: DialogueAgentConfig,
        llm: LlmClient,
        store: ConversationStore,
        context_policy: ContextPolicy,
        input_policy: InputPolicy,
        input_validators: list[InputValidator],
        output_policy: OutputPolicy,
        output_validators: list[OutputValidator],
    ):
        self.config = config
        self.llm = llm
        self.store = store
        self.context_policy = context_policy
        self.input_policy = input_policy
        self.input_validators = input_validators
        self.output_policy = output_policy
        self.output_validators = output_validators
        self._conversation_locks: dict[str, asyncio.Lock] = {}

    @property
    def info(self) -> AgentInfo:
        return AgentInfo(id=self.config.id, name=self.config.name, description=self.config.description)

    async def run(self, command: AgentCommand) -> AgentResult:
        text = self.input_policy.prepare(command.message)
        for validator in self.input_validators:
            validator.validate(text)

        lock = self._conversation_locks.setdefault(command.conversation_id, asyncio.Lock())
        async with lock:
            conversation = await self.store.get(self.config.id, command.conversation_id)
            messages = self.context_policy.build(
                self.config.system_prompt,
                conversation.messages,
                text,
            )
            # Вопрос является частью истории независимо от доступности внешней LLM.
            # Запрос к модели строится до сохранения, поэтому текущий текст не дублируется.
            await self.store.append_message(
                self.config.id,
                command.conversation_id,
                "user",
                text,
            )
            completion = await self.llm.complete(
                messages,
                model=self.config.model,
                temperature=self.config.temperature,
                max_tokens=self.config.max_tokens,
            )

            reply = self.output_policy.present(completion.content)
            for validator in self.output_validators:
                validator.validate(reply)
            await self.store.append_message(
                self.config.id,
                command.conversation_id,
                "assistant",
                reply,
            )

        return AgentResult(
            agent_id=self.config.id,
            reply=reply,
            model=completion.model,
            source=completion.source,
        )
