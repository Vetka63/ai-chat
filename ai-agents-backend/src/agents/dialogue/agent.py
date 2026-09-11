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
from capabilities.context_memory.models import ContextSettings


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
        memory=None,
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
        self.memory = memory
        self._conversation_locks: dict[str, asyncio.Lock] = {}

    @property
    def info(self) -> AgentInfo:
        features = ["persistent_history"]
        if self.accounting:
            features.append("token_accounting")
        if self.memory:
            features.append("context_memory")
        return AgentInfo(id=self.config.id, name=self.config.name, description=self.config.description, capabilities=features)

    async def run(self, command: AgentCommand) -> AgentResult:
        text = self.input_policy.prepare(command.message)
        for validator in self.input_validators:
            validator.validate(text)

        lock = self._conversation_locks.setdefault(command.conversation_id, asyncio.Lock())
        async with lock:
            conversation = await self.store.get(self.config.id, command.conversation_id)
            model_id = command.model_id or conversation.selected_model_id or self.config.model
            spec = self.catalog.get(model_id) if self.catalog else None
            max_tokens = self.output_limit(command, conversation, spec)
            system_prompt = self.config.system_prompt.replace("{provider}", spec.provider if spec else "DeepSeek")
            messages = self.context_policy.build(
                system_prompt,
                conversation.messages,
                text,
            )
            # Сохраняем вопрос до сжатия: summary тоже может завершиться ошибкой API.
            if spec:
                await self.store.select_model(self.config.id, command.conversation_id, model_id)
            await self.store.configure_output(self.config.id, command.conversation_id, max_tokens)
            await self.store.append_message(self.config.id, command.conversation_id, "user", text)
            prepared = await self.memory.prepare(conversation, system_prompt, text, model_id, generate=True) if self.memory else None
            baseline = messages
            if prepared:
                messages = prepared.messages
            run = None
            if self.accounting and spec:
                estimate = await self.estimate_context(messages, baseline, conversation.messages, text, spec, prepared, conversation.context_settings.mode, max_tokens)
                run = RunRecord(id=new_id(), agent_id=self.config.id, conversation_id=command.conversation_id,
                    created_at=now(), model_id=spec.id, provider=spec.provider, requested_model=spec.model,
                    user_index=len(conversation.messages), estimate=estimate, pricing=self.catalog.pricing_at(model_id, now()))
                await self.accounting.record(run)
            started = perf_counter()
            try:
                completion = await self.llm.complete(messages, model=model_id,
                    temperature=self.config.temperature, max_tokens=max_tokens)
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
            additional_runs=prepared.new_runs if prepared else [],
            summary=await self.memory.repository.get(self.config.id, command.conversation_id) if self.memory else None,
            token_savings=await self.accounting.savings(self.config.id, command.conversation_id) if self.accounting else None,
        )

    async def configure_context(self, conversation_id: str, settings: ContextSettings):
        """Меняет стратегию между запусками; текущий запрос не меняется посередине."""
        if settings.mode == "summary" and not self.memory:
            raise AgentError("context_not_supported", "К агенту не подключено сжатие контекста", 501)
        async with self._conversation_locks.setdefault(conversation_id, asyncio.Lock()):
            await self.store.configure_context(self.config.id, conversation_id, settings)

    async def fork_conversation(self, conversation_id: str, settings: ContextSettings):
        """Копия одного согласованного снимка, без вызовов LLM и без изменения оригинала."""
        async with self._conversation_locks.setdefault(conversation_id, asyncio.Lock()):
            source = await self.store.get(self.config.id, conversation_id)
            return await self.store.fork(source, settings)

    def output_limit(self, command, conversation, spec):
        """Явное null отключает лимит; отсутствующее поле использует настройки чата."""
        if "max_output_tokens" in command.model_fields_set:
            value = command.max_output_tokens
        else:
            value = conversation.max_output_tokens if conversation else self.config.max_tokens
        if value is not None and spec and value > spec.max_output_tokens:
            raise AgentError("invalid_output_limit", "Лимит ответа выше максимума выбранной модели; уменьшите его или отключите")
        return value

    async def estimate_context(self, messages, baseline, history, text, spec, prepared, mode, max_tokens):
        """Сопоставляет полный и реально отправляемый контекст одним tokenizer."""
        def calculate():
            result = self.accounting.estimate(messages, history, text, spec, max_tokens)
            counter = self.accounting.estimators[spec.provider]
            result.context_mode = mode
            result.full_prompt_tokens = counter.count_messages(baseline)
            if prepared:
                result.pending_summary = prepared.pending_summary
                for key, value in (prepared.progress or {}).items():
                    setattr(result, key, value)
                if prepared.summary:
                    result.summary_tokens = counter.count_text(prepared.summary.text)
                    result.summarized_messages = prepared.summary.covered_messages
                    result.summary_revision = prepared.summary.revision
            return result
        return await asyncio.to_thread(calculate)

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
        prepared = await self.memory.prepare(conversation, self.config.system_prompt.replace("{provider}", spec.provider), text, spec.id, generate=False) if self.memory and conversation else None
        return await self.estimate_context(prepared.messages if prepared else messages, messages, history, text, spec,
                                          prepared, conversation.context_settings.mode if conversation else "full", self.output_limit(command, conversation, spec))
