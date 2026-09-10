"""Инкапсулированный сценарий диалогового агента с постоянным контекстом."""

import asyncio
from time import perf_counter

from agent_core.contracts import (
    ContextPolicy,
    ConversationStore,
    InputPolicy,
    InputValidator,
    LlmClient,
    OutputPolicy,
    OutputValidator,
)
from agent_core.models import AgentCommand, AgentError, AgentInfo, AgentResult, PreviewCommand, now, new_id
from agents.dialogue.config import DialogueAgentConfig
from capabilities.token_accounting.models import RunRecord
from capabilities.token_accounting.service import TokenAccounting


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
        catalog=None,
        accounting: TokenAccounting | None = None,
    ):
        self.config = config
        self.llm = llm
        self.store = store
        self.context_policy = context_policy
        self.input_policy = input_policy
        self.input_validators = input_validators
        self.output_policy = output_policy
        self.output_validators = output_validators
        self.catalog = catalog
        self.accounting = accounting
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
            model_id = command.model_id or conversation.selected_model_id or self.config.model
            spec = self.catalog.get(model_id) if self.catalog else None
            system_prompt = self.config.system_prompt.replace("{provider}", spec.provider if spec else "DeepSeek")
            messages = self.context_policy.build(
                system_prompt,
                conversation.messages,
                text,
            )
            run = None
            if self.accounting and spec:
                estimate = await asyncio.to_thread(self.accounting.estimate, messages, conversation.messages, text, spec, self.config.max_tokens)
                run = RunRecord(id=new_id(), agent_id=self.config.id, conversation_id=command.conversation_id,
                    created_at=now(), model_id=spec.id, provider=spec.provider, requested_model=spec.model,
                    user_index=len(conversation.messages), estimate=estimate, pricing=self.catalog.pricing_at(model_id, now()))
                await self.accounting.record(run)
            if spec:
                await self.store.select_model(self.config.id, command.conversation_id, model_id)
            # Вопрос является частью истории независимо от доступности внешней LLM.
            # Запрос к модели строится до сохранения, поэтому текущий текст не дублируется.
            await self.store.append_message(
                self.config.id,
                command.conversation_id,
                "user",
                text,
            )
            started = perf_counter()
            try:
                completion = await self.llm.complete(messages, model=model_id,
                    temperature=self.config.temperature, max_tokens=self.config.max_tokens)
                if run:
                    run.usage, run.finish_reason = completion.usage, completion.finish_reason
                    run.returned_model = completion.model
                    run.pricing = self.catalog.pricing_at(model_id, run.created_at, completion.model)
                reply = self.output_policy.present(completion.content)
                for validator in self.output_validators:
                    validator.validate(reply)
                await self.store.append_message(self.config.id, command.conversation_id, "assistant", reply)
                if run:
                    run.status, run.assistant_index = "success", len(conversation.messages) + 1
            except AgentError as exc:
                if run:
                    run.status, run.error_code, run.error_message = "error", exc.code, exc.message
                    run.provider_status = exc.provider_status
                raise
            finally:
                if run:
                    run.duration_ms = round((perf_counter() - started) * 1000)
                    await self.accounting.record(run)

        return AgentResult(
            agent_id=self.config.id,
            reply=reply,
            model=completion.model,
            source=completion.source,
            run=run,
        )

    async def preview(self, command: PreviewCommand):
        """Оценивает будущий запрос без сохранения сообщения и обращения к LLM."""
        if not self.accounting or not self.catalog:
            raise AgentError("accounting_disabled", "У этого агента не подключён учёт токенов", 501)
        conversation = await self.store.get(self.config.id, command.conversation_id) if command.conversation_id else None
        history = conversation.messages if conversation else []
        selected = conversation.selected_model_id if conversation else None
        spec = self.catalog.get(command.model_id or selected or self.config.model, False)
        text = self.input_policy.prepare(command.message)
        messages = self.context_policy.build(self.config.system_prompt.replace("{provider}", spec.provider), history, text)
        return await asyncio.to_thread(self.accounting.estimate, messages, history, text, spec, self.config.max_tokens)
