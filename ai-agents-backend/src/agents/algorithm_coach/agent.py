"""Самостоятельный алгоритмический агент с тремя слоями памяти."""
import asyncio
import json

from agent_core.models import AgentError, AgentInfo, AgentResult, Message, new_id
from capabilities.context_memory.models import ContextSettings
from capabilities.personalization.context import profile_data, profile_text


class AlgorithmCoachAgent:
    """Оркестрирует диалог и явный анализ памяти, не хранит состояние задачи в экземпляре."""
    def __init__(self, config, store, memory, calls, catalog, accounting, input_policy, output_policy, context_policy, workflow, invariants):
        self.config, self.store, self.memory = config, store, memory
        self.calls, self.catalog, self.accounting = calls, catalog, accounting
        self.input_policy, self.output_policy, self.context_policy = input_policy, output_policy, context_policy
        self._locks = {}
        self.workflow = workflow
        self.invariants = invariants

    @property
    def info(self):
        return AgentInfo(id=self.config.id, name=self.config.name, description=self.config.description,
            capabilities=['persistent_history', 'token_accounting', 'memory_layers', 'personalization', 'task_workflow', 'invariants'])

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

    async def estimate(self, messages, workspace, text, spec, max_tokens):
        def calculate():
            history = [Message(role=m.role, content=m.content) for m in workspace.short_term]
            estimate = self.accounting.estimate(messages, history, text, spec, max_tokens)
            working, long_term = self.context_policy.blocks(workspace)
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
        command = command.model_copy(update={'command_id': command.command_id or new_id()})
        async with self._locks.setdefault(command.conversation_id, asyncio.Lock()):
            conversation = await self.store.get(self.config.id, command.conversation_id)
            workspace = await self.memory.repository.workspace(self.config.id, command.conversation_id)
            spec = self.catalog.get(command.model_id or conversation.selected_model_id or self.config.model)
            limit = self.output_limit(command, conversation, spec)
            messages = self.context_policy.build(self.config.system_prompt.replace('{provider}', spec.provider), workspace, text)
            estimate = await self.estimate(messages, workspace, text, spec, limit)
            reservation = await self.workflow.repository.reserve(workspace, command, spec.id, limit)
            if isinstance(reservation, AgentResult):
                return reservation
            additional_runs = []
            async def commit(record):
                result = AgentResult(agent_id=self.config.id, reply=reply, model=completion.model, source=completion.source, run=record, additional_runs=additional_runs)
                await self.workflow.repository.complete(workspace, command, result)
            try:
                checked = await self.invariants.check(workspace, text, 'input', command.command_id)
                if checked:
                    additional_runs.append(checked)
                async with self.calls.invoke(agent_id=self.config.id, conversation_id=conversation.id,
                    messages=messages, spec=spec, estimate=estimate, user_index=workspace.history_message_count,
                    temperature=self.config.temperature, max_tokens=limit, run_id=reservation, commit=commit,
                    memory_context=self.context_policy.snapshot(workspace)) as (completion, run):
                    reply = self.output_policy.candidate(completion, workspace.workflow.state.phase)
                    checked = await self.invariants.check(workspace, reply, 'output', command.command_id, request=text)
                    if checked:
                        additional_runs.append(checked)
                    run.assistant_index = workspace.history_message_count + 1
            except BaseException as exc:
                await self.workflow.repository.fail(workspace.task.id, command.command_id,
                    'interrupted' if isinstance(exc, asyncio.CancelledError) else 'error')
                raise
            return AgentResult(agent_id=self.config.id, reply=reply, model=completion.model, source=completion.source, run=run, additional_runs=additional_runs)

    async def save_artifact(self, conversation_id, command):
        """Ручная форма проходит те же проверки правил, что и кандидат от модели."""
        workspace = await self.memory.repository.workspace(self.config.id, conversation_id)
        reservation = await self.workflow.repository.reserve_artifact(workspace, command, self.workflow.policy)
        if reservation is not None:
            return reservation
        try:
            text = json.dumps(command.content, ensure_ascii=False) if command.kind != 'solution' else command.content.get('text', '')
            await self.invariants.check(workspace, text, 'artifact', command.command_id,
                require_solution=command.kind == 'solution', request='Сохранение артефакта '+command.kind)
            return await self.workflow.repository.change(self.config.id, conversation_id, 'artifact', command,
                self.workflow.policy, reserved=True, memory_versions=self.memory.repository.versions(workspace),
                checked_revision=workspace.workflow.state.revision)
        except BaseException as exc:
            await self.workflow.repository.fail(workspace.task.id, command.command_id,
                'interrupted' if isinstance(exc, asyncio.CancelledError) else 'error')
            raise

    async def preview(self, command):
        if not command.conversation_id:
            raise AgentError('task_required', 'Создайте задачу для оценки всех слоёв памяти')
        conversation = await self.store.get(self.config.id, command.conversation_id)
        workspace = await self.memory.repository.workspace(self.config.id, conversation.id)
        spec = self.catalog.get(command.model_id or conversation.selected_model_id or self.config.model, False)
        messages = self.context_policy.build(self.config.system_prompt.replace('{provider}', spec.provider), workspace, command.message)
        return await self.estimate(messages, workspace, command.message, spec, self.output_limit(command, conversation, spec))

    async def propose_memory(self, conversation_id, command):
        """Один дополнительный LLM-вызов только по явному нажатию пользователя."""
        async with self._locks.setdefault(conversation_id, asyncio.Lock()):
            conversation = await self.store.get(self.config.id, conversation_id)
            workspace = await self.memory.repository.workspace(self.config.id, conversation_id)
            self.workflow.policy.require_active(workspace.workflow.state)
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
