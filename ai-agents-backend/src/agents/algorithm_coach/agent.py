"""Самостоятельный алгоритмический агент с тремя слоями памяти."""
import asyncio
import json

from agent_core.models import AgentError, AgentInfo, AgentResult, Message
from capabilities.context_memory.models import ContextSettings
from capabilities.personalization.context import profile_data, profile_text


class AlgorithmCoachAgent:
    """Оркестрирует диалог и явный анализ памяти, не хранит состояние задачи в экземпляре."""
    def __init__(self, config, store, memory, calls, catalog, accounting, input_policy, output_policy, context_policy, workflow=None):
        self.config, self.store, self.memory = config, store, memory
        self.calls, self.catalog, self.accounting = calls, catalog, accounting
        self.input_policy, self.output_policy, self.context_policy = input_policy, output_policy, context_policy
        self.workflow = workflow
        self._locks = {}

    @property
    def info(self):
        return AgentInfo(id=self.config.id, name=self.config.name, description=self.config.description,
            capabilities=['persistent_history', 'token_accounting', 'memory_layers', 'personalization',
                          'task_workflow'] if self.workflow else
                         ['persistent_history', 'token_accounting', 'memory_layers', 'personalization'])

    async def create_conversation(self, title, settings, problem=None, profile_id=None):
        if settings and settings.mode != 'sliding_window':
            raise AgentError('unsupported_context', 'У наставника используются три слоя памяти и окно последних сообщений')
        settings = settings or ContextSettings(mode='sliding_window', keep_last=4)
        problem = self.memory.policy.validate_problem(problem or {})
        return await self.memory.repository.create(self.config.id, title, settings, problem, profile_id or 'local')

    def output_limit(self, command, conversation, spec):
        value = command.max_output_tokens if 'max_output_tokens' in command.model_fields_set else conversation.max_output_tokens
        if value is not None and value > spec.max_output_tokens:
            raise AgentError('invalid_output_limit', 'Лимит ответа выше максимума выбранной модели')
        return value

    async def estimate(self, messages, workspace, text, spec, max_tokens, workflow=None):
        def calculate():
            history = [Message(role=m.role, content=m.content) for m in workspace.short_term]
            estimate = self.accounting.estimate(messages, history, text, spec, max_tokens)
            working, long_term = self.context_policy.blocks(workspace, workflow)
            counter = self.accounting.estimators[spec.provider]
            estimate.context_mode = 'memory_layers'
            estimate.working_memory_tokens = counter.count_text(working)
            estimate.long_term_memory_tokens = counter.count_text(long_term)
            estimate.profile_tokens = counter.count_text(profile_text(workspace.profile))
            estimate.history_message_count = workspace.history_message_count
            estimate.retained_message_count = len(history)
            estimate.discarded_message_count = workspace.history_message_count - len(history)
            return estimate
        return await asyncio.to_thread(calculate)

    async def run(self, command):
        text = self.input_policy.prepare(command.message)
        async with self._locks.setdefault(command.conversation_id, asyncio.Lock()):
            conversation = await self.store.get(self.config.id, command.conversation_id)
            workspace = await self.memory.repository.workspace(self.config.id, command.conversation_id)
            workflow = await self.workflow.workspace(self.config.id, command.conversation_id) if self.workflow else None
            if workflow and (workflow.state.status == 'paused' or workflow.state.phase == 'done'):
                raise AgentError('task_unavailable', 'Задача на паузе или завершена. Продолжите её для общения', 409)
            spec = self.catalog.get(command.model_id or conversation.selected_model_id or self.config.model)
            limit = self.output_limit(command, conversation, spec)
            messages = self.context_policy.build(self.config.system_prompt.replace('{provider}', spec.provider), workspace, text, workflow)
            estimate = await self.estimate(messages, workspace, text, spec, limit, workflow)
            await self.store.select_model(self.config.id, conversation.id, spec.id)
            await self.store.configure_output(self.config.id, conversation.id, limit)
            await self.store.append_message(self.config.id, conversation.id, 'user', text)
            if workflow:
                await self.workflow.clear_candidate(self.config.id, conversation.id, workflow.state.revision)
            async with self.calls.invoke(agent_id=self.config.id, conversation_id=conversation.id,
                messages=messages, spec=spec, estimate=estimate, user_index=workspace.history_message_count,
                temperature=self.config.temperature, max_tokens=limit,
                memory_context=self.context_policy.snapshot(workspace)) as (completion, run):
                reply = self.output_policy.present(completion)
                await self.memory.repository.append_reply(workspace, reply,
                    workflow.state.revision if workflow else None)
                run.assistant_index = workspace.history_message_count + 1
            return AgentResult(agent_id=self.config.id, reply=reply, model=completion.model, source=completion.source, run=run)

    async def preview(self, command):
        if not command.conversation_id:
            raise AgentError('task_required', 'Создайте задачу для оценки всех слоёв памяти')
        conversation = await self.store.get(self.config.id, command.conversation_id)
        workspace = await self.memory.repository.workspace(self.config.id, conversation.id)
        workflow = await self.workflow.workspace(self.config.id, conversation.id) if self.workflow else None
        spec = self.catalog.get(command.model_id or conversation.selected_model_id or self.config.model, False)
        messages = self.context_policy.build(self.config.system_prompt.replace('{provider}', spec.provider), workspace, command.message, workflow)
        return await self.estimate(messages, workspace, command.message, spec, self.output_limit(command, conversation, spec), workflow)

    async def propose_memory(self, conversation_id, command):
        """Один дополнительный LLM-вызов только по явному нажатию пользователя."""
        async with self._locks.setdefault(conversation_id, asyncio.Lock()):
            conversation = await self.store.get(self.config.id, conversation_id)
            workspace = await self.memory.repository.workspace(self.config.id, conversation_id)
            if (workspace.task.revision, workspace.profile.memory_revision, workspace.profile.revision) != (
                    command.task_revision, command.profile_revision, command.preferences_revision):
                raise AgentError('state_conflict', 'Обновите панель памяти перед анализом', 409)
            source = await self.memory.repository.source(self.config.id, conversation_id, command.source_message_id)
            payload_data = {'user_message': source.content, 'profile': profile_data(workspace.profile),
                'existing_working': {e.key: e.value for e in workspace.working if e.active},
                'existing_long_term': {e.key: e.value for e in workspace.long_term if e.active}}
            payload = json.dumps(payload_data, ensure_ascii=False)
            messages = [Message(role='system', content=self.config.proposals_prompt), Message(role='user', content=payload)]
            spec = self.catalog.get(conversation.selected_model_id or self.config.model)
            estimate = await asyncio.to_thread(self.accounting.estimate, messages, [], payload, spec, conversation.max_output_tokens)
            estimate.profile_tokens = self.accounting.estimators[spec.provider].count_text(profile_text(workspace.profile))
            # Индекс исходного сообщения используется для отчёта, а ID — для происхождения записи.
            index = await self.memory.repository.source_index(self.config.id, conversation_id, source.id)
            async with self.calls.invoke(agent_id=self.config.id, conversation_id=conversation_id,
                messages=messages, spec=spec, estimate=estimate, user_index=index, temperature=0,
                max_tokens=conversation.max_output_tokens, purpose='memory_proposals',
                memory_context={'source_message_id': source.id, 'task_id': workspace.task.id,
                    'task_revision': workspace.task.revision, 'profile_revision': workspace.profile.memory_revision,
                    **payload_data, 'profile': workspace.profile.model_dump()}) as (completion, run):
                candidates = self.output_policy.proposals(completion, self.memory.policy)
                await self.memory.repository.save_proposals(workspace, source.id, candidates)
            return {'workspace': await self.memory.repository.workspace(self.config.id, conversation_id), 'run': run}
