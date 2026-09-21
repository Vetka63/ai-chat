"""Самостоятельный алгоритмический агент с тремя слоями памяти."""
import asyncio
import json
import logging

from agent_core.models import AgentError, AgentInfo, AgentResult, Message
from capabilities.context_memory.models import ContextSettings
from capabilities.personalization.context import profile_data, profile_text


logger = logging.getLogger(__name__)


class AlgorithmCoachAgent:
    """Оркестрирует диалог и явный анализ памяти, не хранит состояние задачи в экземпляре."""
    def __init__(self, config, store, memory, calls, catalog, accounting, input_policy, output_policy,
                 context_policy, workflow=None, invariants=None, lifecycle_policy=None):
        self.config, self.store, self.memory = config, store, memory
        self.calls, self.catalog, self.accounting = calls, catalog, accounting
        self.input_policy, self.output_policy, self.context_policy = input_policy, output_policy, context_policy
        self.workflow = workflow
        self.invariants = invariants
        self.lifecycle_policy = lifecycle_policy
        self._locks = {}

    @property
    def info(self):
        return AgentInfo(id=self.config.id, name=self.config.name, description=self.config.description,
            capabilities=['persistent_history', 'token_accounting', 'memory_layers', 'personalization',
                          *(['task_workflow'] if self.workflow else []),
                          *(['invariants'] if self.invariants else []),
                          *(['controlled_lifecycle'] if self.lifecycle_policy else [])])

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
            invariants = await self.invariants.repository.workspace(self.config.id, command.conversation_id) if self.invariants else None
            if workflow and (workflow.state.status == 'paused' or workflow.state.phase == 'done'):
                raise AgentError('task_unavailable', 'Задача на паузе или завершена. Продолжите её для общения', 409)
            if workflow and self.lifecycle_policy:
                self.lifecycle_policy.ensure_dialogue_allowed(workflow)
            lifecycle_refusal = self.lifecycle_policy.validate_input(text, workflow) if workflow and self.lifecycle_policy else None
            lifecycle_status = (self.lifecycle_policy.status_response(text, workflow)
                if workflow and self.lifecycle_policy and not lifecycle_refusal else None)
            lifecycle_response = lifecycle_refusal or lifecycle_status
            spec = self.catalog.get(command.model_id or conversation.selected_model_id or self.config.model)
            limit = self.output_limit(command, conversation, spec)
            await self.store.select_model(self.config.id, conversation.id, spec.id)
            await self.store.configure_output(self.config.id, conversation.id, limit)
            await self.store.append_message(self.config.id, conversation.id, 'user', text)
            requested_stage = (self.lifecycle_policy.requested_stage(text)
                if workflow and self.lifecycle_policy else None)
            replace_candidate = (workflow is not None and not lifecycle_response
                and requested_stage == workflow.state.phase)
            if replace_candidate:
                await self.workflow.clear_candidate(self.config.id, conversation.id, workflow.state.revision)
                workflow = await self.workflow.workspace(self.config.id, command.conversation_id)
            extra_runs = []
            if lifecycle_response:
                await self.memory.repository.append_reply(workspace, lifecycle_response,
                    workflow.state.revision, invariants.revision if invariants else None, candidate=False)
                return AgentResult(agent_id=self.config.id, reply=lifecycle_response,
                    model='Жизненный цикл задачи', source='policy')
            messages = self.context_policy.build(self.config.system_prompt.replace('{provider}', spec.provider),
                workspace, text, workflow, invariants)
            estimate = await self.estimate(messages, workspace, text, spec, limit, workflow)
            if self.invariants:
                _, check_run, refusal = await self.invariants.check(self.config.id, conversation.id,
                    workspace.task, workflow, invariants, text, 'input',
                    profile=workspace.profile, user_index=workspace.history_message_count)
                if check_run:
                    extra_runs.append(check_run)
                if refusal:
                    await self.memory.repository.append_reply(workspace, refusal,
                        workflow.state.revision if workflow else None, invariants.revision, candidate=False)
                    return AgentResult(agent_id=self.config.id, reply=refusal, model='Правила задачи',
                        source='policy', additional_runs=extra_runs)
            async def persist_result(output_completion, output_run, output_reply, output_issue):
                lifecycle_output_refusal = (self.lifecycle_policy.output_failure(workflow)
                    if output_issue and self.lifecycle_policy else None)
                refusal = lifecycle_output_refusal
                if lifecycle_output_refusal:
                    output_reply = lifecycle_output_refusal
                if self.invariants and not refusal:
                    _, check_run, refusal = await self.invariants.check(self.config.id, conversation.id,
                        workspace.task, workflow, invariants, output_reply, 'output', request=text,
                        profile=workspace.profile, user_index=workspace.history_message_count)
                    if check_run:
                        extra_runs.append(check_run)
                    if refusal:
                        output_reply = refusal
                candidate = not refusal and (not self.lifecycle_policy
                    or self.lifecycle_policy.can_be_candidate(text, output_reply, workflow))
                await self.memory.repository.append_reply(workspace, output_reply,
                    workflow.state.revision if workflow else None,
                    invariants.revision if invariants else None, candidate=candidate)
                output_run.assistant_index = workspace.history_message_count + 1
                return (output_completion, output_run, output_reply, refusal,
                    lifecycle_output_refusal)

            snapshot = self.context_policy.snapshot(workspace, invariants, workflow)
            async with self.calls.invoke(agent_id=self.config.id, conversation_id=conversation.id,
                messages=messages, spec=spec, estimate=estimate, user_index=workspace.history_message_count,
                temperature=self.config.temperature, max_tokens=limit,
                memory_context=snapshot) as (completion, run):
                reply = self.output_policy.present(completion)
                issue = (self.lifecycle_policy.output_issue(text, reply, workflow)
                    if workflow and self.lifecycle_policy else None)
                if issue:
                    logger.warning('lifecycle_output_retry agent=%s conversation=%s phase=%s reason=%s',
                        self.config.id, conversation.id, workflow.state.phase, issue)
                    extra_runs.append(run)
                    retry_messages = self.context_policy.corrective_retry(messages, reply, issue, workflow)
                    retry_estimate = await self.estimate(
                        retry_messages, workspace, text, spec, limit, workflow)
                    retry_context = {**snapshot, 'lifecycle_retry': {'reason': issue}}
                    async with self.calls.invoke(agent_id=self.config.id, conversation_id=conversation.id,
                        messages=retry_messages, spec=spec, estimate=retry_estimate,
                        user_index=workspace.history_message_count,
                        temperature=self.config.temperature, max_tokens=limit,
                        memory_context=retry_context) as (retry_completion, retry_run):
                        retry_reply = self.output_policy.present(retry_completion)
                        retry_issue = self.lifecycle_policy.output_issue(text, retry_reply, workflow)
                        if retry_issue:
                            logger.error('lifecycle_output_rejected agent=%s conversation=%s phase=%s reason=%s',
                                self.config.id, conversation.id, workflow.state.phase, retry_issue)
                        completion, run, reply, refusal, lifecycle_output_refusal = await persist_result(
                            retry_completion, retry_run, retry_reply, retry_issue)
                else:
                    completion, run, reply, refusal, lifecycle_output_refusal = await persist_result(
                        completion, run, reply, None)
            return AgentResult(agent_id=self.config.id, reply=reply,
                model=('Жизненный цикл задачи' if lifecycle_output_refusal else 'Правила задачи') if refusal else completion.model,
                source='policy' if refusal else completion.source, run=run, additional_runs=extra_runs)

    async def apply_workflow(self, conversation_id, command):
        """Редактируемый артефакт не обходит проверку правил при подтверждении в UI."""
        async with self._locks.setdefault(conversation_id, asyncio.Lock()):
            flow = await self.workflow.workspace(self.config.id, conversation_id)
            if flow.state.revision != command.expected_revision:
                raise AgentError('state_conflict', 'Состояние задачи изменилось. Обновите панель', 409)
            option = self.workflow.policy.require(command.action, flow.state.phase, flow.state.status,
                flow.state.candidate_message_id, flow.control, flow.task_revision, flow.invariant_revision)
            if not option.allowed:
                raise AgentError('transition_not_allowed', option.reason or 'Переход недоступен', 409)
            if command.action in ('accept_plan', 'accept_solution', 'accept_validation'):
                content = command.content or {}
                material = '\n'.join(content.get('steps', [])) if command.action == 'accept_plan' and isinstance(content.get('steps'), list) else content.get('text', '')
                if not isinstance(material, str) or not material.strip():
                    raise AgentError('invalid_artifact', 'Результат для сохранения пуст', 422)
                if self.lifecycle_policy:
                    self.lifecycle_policy.validate_artifact(command.action, material)
            if self.invariants and command.action in ('accept_plan', 'accept_solution', 'accept_validation'):
                memory_workspace = await self.memory.repository.workspace(self.config.id, conversation_id)
                task = memory_workspace.task
                snapshot = await self.invariants.repository.workspace(self.config.id, conversation_id)
                _, _, refusal = await self.invariants.check(self.config.id, conversation_id,
                    task, flow, snapshot, material, 'artifact', request='Подтверждение результата текущего этапа',
                    profile=memory_workspace.profile, user_index=memory_workspace.history_message_count)
                if refusal:
                    raise AgentError('invariant_conflict', refusal, 422)
            return await self.workflow.apply(self.config.id, conversation_id, command)

    async def save_problem(self, conversation_id, command):
        """Новое условие аннулирует утверждения и возвращает задачу к планированию."""
        async with self._locks.setdefault(conversation_id, asyncio.Lock()):
            problem = self.memory.policy.validate_problem(command.problem)
            await self.workflow.replace_problem(self.config.id, conversation_id, problem, command)

    async def preview(self, command):
        if not command.conversation_id:
            raise AgentError('task_required', 'Создайте задачу для оценки всех слоёв памяти')
        conversation = await self.store.get(self.config.id, command.conversation_id)
        workspace = await self.memory.repository.workspace(self.config.id, conversation.id)
        workflow = await self.workflow.workspace(self.config.id, conversation.id) if self.workflow else None
        invariants = await self.invariants.repository.workspace(self.config.id, conversation.id) if self.invariants else None
        spec = self.catalog.get(command.model_id or conversation.selected_model_id or self.config.model, False)
        messages = self.context_policy.build(self.config.system_prompt.replace('{provider}', spec.provider),
            workspace, command.message, workflow, invariants)
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
