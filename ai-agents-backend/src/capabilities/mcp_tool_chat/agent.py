"""Общий цикл агента: выбор инструмента моделью → MCP → итоговый ответ."""

import asyncio
import json
import logging
import re
from time import perf_counter

from agent_core.models import AgentCommand, AgentError, AgentInfo, AgentResult, Message, ToolCall, now, new_id
from capabilities.mcp_discovery.models import McpToolEvent
from capabilities.mcp_discovery.service import McpDiscoveryError
from capabilities.token_accounting.models import ContextEstimate, RunRecord

logger = logging.getLogger(__name__)


class McpToolChatAgent:
    """Переиспользуемый цикл инструментов без знаний о конкретном MCP-домене."""

    ID = ""

    def __init__(self, llm, store, catalog, accounting, gateway, events, default_model: str,
                 system_prompt: str, lookup_policy=None,
                 allowed_server_ids: set[str] | None = None):
        self.llm, self.store, self.catalog = llm, store, catalog
        self.accounting, self.gateway, self.events = accounting, gateway, events
        self.default_model = default_model
        self.lookup_policy = lookup_policy
        self.system_prompt = system_prompt
        self.allowed_server_ids = allowed_server_ids
        self._locks: dict[str, asyncio.Lock] = {}

    def _check_server(self, server_id: str) -> None:
        """Не позволяет одному агенту использовать инструменты другого дня."""
        self.gateway.chat_server(server_id)
        if self.allowed_server_ids is not None and server_id not in self.allowed_server_ids:
            raise AgentError("mcp_server_not_allowed", "MCP-сервер недоступен этому агенту", 422)

    def _should_offer_tools(self, text: str) -> bool:
        return True

    def _initial_tool_choice(self, text: str, tools: list[dict]) -> str:
        return "auto" if tools else "none"

    def _initial_tools(self, text: str, tools: list[dict]) -> list[dict]:
        """Доменный агент может показать модели только первый допустимый шаг."""
        return tools

    def _tool_arguments(self, conversation, user_index: int, tool_name: str,
                        arguments: dict) -> dict:
        return arguments

    def _max_tool_calls(self) -> int:
        return 3

    def _max_initial_tool_calls(self) -> int:
        return self._max_tool_calls()

    def _next_tool_call(self, conversation, user_index: int,
                        events: list[McpToolEvent], mapping: dict) -> ToolCall | None:
        """По умолчанию дополнительной цепочки нет; Day 17 не меняет поведение."""
        return None

    def _reply_without_tool(self, text: str, tools: list[dict]) -> str | None:
        """Специализированный агент может остановить ложное заявление о записи."""
        return None

    @property
    def info(self) -> AgentInfo:
        raise NotImplementedError("Доменный агент должен описать себя")

    async def create_conversation_with_tools(self, body):
        """Проверяет начальный набор MCP-серверов нового чата."""

        if body.problem is not None or body.profile_id is not None:
            raise AgentError("invalid_mcp_chat", "Карточка задачи и профиль не используются в MCP-чате")
        if body.context_settings.mode != "full":
            raise AgentError("invalid_mcp_context", "Для MCP-чата пока доступна полная история")
        ids = body.mcp_server_ids
        if len(ids) != len(set(ids)):
            raise AgentError("invalid_mcp_servers", "MCP-серверы не должны повторяться")
        for server_id in ids:
            self._check_server(server_id)
        return await self.store.create(self.ID, body.title, body.context_settings, ids)

    @staticmethod
    def _alias(server_id: str, tool_name: str) -> str:
        """Формирует уникальное безопасное имя функции для LLM API."""

        return re.sub(r"[^a-zA-Z0-9_]", "_", f"{server_id}__{tool_name}")

    async def _tools(self, server_ids: list[str]):
        """Преобразует реальные объявления MCP в функцию, доступную модели."""

        if not server_ids:
            return [], {}
        definitions = []
        mapping = {}
        for server_id in server_ids:
            self._check_server(server_id)
            discovered = await self.gateway.discover(server_id)
            for tool in discovered.tools:
                alias = self._alias(server_id, tool.name)
                if alias in mapping:
                    raise AgentError("mcp_name_collision", "Названия MCP-инструментов совпали", 502)
                mapping[alias] = (server_id, tool.name)
                definitions.append({"type": "function", "function": {
                    "name": alias,
                    "description": tool.description or f"Инструмент {tool.name} сервера {discovered.server_name}",
                    "parameters": tool.input_schema,
                }})
        if not definitions:
            raise AgentError("mcp_no_tools", "Выбранный MCP-сервер не объявил инструментов", 502)
        return definitions, mapping

    def _estimate(self, messages, tools, history, text, spec, max_tokens):
        """Локально оценивает JSON-запрос; API usage остаётся источником фактических чисел."""

        estimator = self.accounting.estimators[spec.provider]
        prompt = estimator.count_text(json.dumps({"messages": messages, "tools": tools}, ensure_ascii=False))
        return ContextEstimate(
            current_message_tokens=estimator.count_text(text),
            history_tokens=sum(estimator.count_text(item.content) for item in history),
            system_tokens=estimator.count_text(self.system_prompt),
            prompt_tokens=prompt, reserved_output_tokens=max_tokens,
            context_window=spec.context_window,
            occupancy_percent=round(100 * (prompt + (max_tokens or 0)) / spec.context_window, 2),
            exceeds_context=prompt + (max_tokens or 0) > spec.context_window,
            method=f"{estimator.method}; MCP tool JSON — приблизительно",
        )

    async def _complete(self, messages, tools, history, text, spec, max_tokens,
                        conversation_id, user_index, purpose, tool_choice):
        """Выполняет один платный LLM-вызов и записывает его отдельный usage."""

        run = RunRecord(id=new_id(), agent_id=self.ID, conversation_id=conversation_id,
                        created_at=now(), model_id=spec.id, provider=spec.provider,
                        requested_model=spec.model, user_index=user_index,
                        estimate=self._estimate(messages, tools, history, text, spec, max_tokens),
                        pricing=self.catalog.pricing_at(spec.id, now()), purpose=purpose)
        await self.accounting.record(run)
        started = perf_counter()
        try:
            answer = await self.llm.complete_with_tools(messages, tools, model=spec.id,
                                                        temperature=0.2, max_tokens=max_tokens,
                                                        tool_choice=tool_choice)
            run.usage, run.finish_reason, run.returned_model = answer.usage, answer.finish_reason, answer.model
            run.pricing = self.catalog.pricing_at(spec.id, run.created_at, answer.model)
            run.status = "success"
            return answer, run
        except AgentError as exc:
            run.status, run.error_code, run.error_message = "error", exc.code, exc.message
            run.provider_status = exc.provider_status
            raise
        finally:
            run.duration_ms = round((perf_counter() - started) * 1000)
            await self.accounting.record(run)

    async def run(self, command: AgentCommand) -> AgentResult:
        """Сохраняет вопрос, исполняет разрешённые tool_calls и сохраняет ответ."""

        text = command.message.strip()
        if not text or len(text) > 10000:
            raise AgentError("invalid_message", "Сообщение должно содержать от 1 до 10000 символов")
        async with self._locks.setdefault(command.conversation_id, asyncio.Lock()):
            conversation = await self.store.get(self.ID, command.conversation_id)
            selected_ids = (command.mcp_server_ids if command.mcp_server_ids is not None
                            else conversation.mcp_server_ids)
            if len(selected_ids) != len(set(selected_ids)):
                raise AgentError("invalid_mcp_servers", "MCP-серверы не должны повторяться")
            for server_id in selected_ids:
                self._check_server(server_id)
            model_id = command.model_id or conversation.selected_model_id or self.default_model
            spec = self.catalog.get(model_id)
            max_tokens = command.max_output_tokens if "max_output_tokens" in command.model_fields_set else conversation.max_output_tokens
            if max_tokens is not None and max_tokens > spec.max_output_tokens:
                raise AgentError("invalid_output_limit", "Лимит ответа превышает максимум модели")
            await self.store.select_model(self.ID, conversation.id, model_id)
            await self.store.configure_output(self.ID, conversation.id, max_tokens)
            await self.store.append_message(self.ID, conversation.id, "user", text, mcp_server_ids=selected_ids)
            discovered_tools, mapping = await self._tools(selected_ids) if self._should_offer_tools(text) else ([], {})
            tools = self._initial_tools(text, discovered_tools)
            policy_reply = self._reply_without_tool(text, tools)
            if policy_reply is not None:
                await self.store.append_message(self.ID, conversation.id, "assistant", policy_reply)
                return AgentResult(agent_id=self.ID, reply=policy_reply, model="policy",
                                   source="policy")
            messages = [{"role": "system", "content": self.system_prompt}]
            messages.extend({"role": item.role, "content": item.content} for item in conversation.messages)
            messages.append({"role": "user", "content": text})
            user_index = len(conversation.messages)
            initial_choice = self._initial_tool_choice(text, tools)
            first, first_run = await self._complete(messages, tools, conversation.messages, text, spec, max_tokens,
                                                    conversation.id, user_index,
                                                    "mcp_selection" if tools else "mcp_answer",
                                                    initial_choice)
            events = []
            final, final_run = first, first_run
            selection = first
            additional_runs: list[RunRecord] = []
            if tools and not first.tool_calls:
                if initial_choice == "required":
                    raise AgentError("mcp_tool_not_selected", "Модель не вызвала инструмент для создания отчёта", 502)
                retry = self.lookup_policy.retry(text, first.content, tools, mapping) if self.lookup_policy else None
                if retry:
                    logger.info("mcp_lookup_retry conversation_id=%s reason=%s tools=%s",
                                conversation.id, retry.reason, len(retry.tools))
                    forced, forced_run = await self._complete(
                        messages, retry.tools, conversation.messages, text, spec, max_tokens,
                        conversation.id, user_index, "mcp_selection_retry", "required",
                    )
                    if forced.tool_calls:
                        selection = forced
                        additional_runs = [first_run, forced_run]
                    else:
                        logger.warning("mcp_lookup_retry_without_call conversation_id=%s reason=%s",
                                       conversation.id, retry.reason)
                        if retry.reason == "explicit_catalog_request":
                            raise AgentError("mcp_tool_not_selected", "Модель не смогла выбрать поиск по каталогу", 502)
                        additional_runs = [forced_run]
            if selection.tool_calls:
                if not additional_runs:
                    additional_runs = [first_run]
                if len(selection.tool_calls) > self._max_initial_tool_calls():
                    raise AgentError("mcp_too_many_calls", "Модель запросила слишком много инструментов", 502)
                offered_names = {tool["function"]["name"] for tool in tools}
                if any(call.name not in offered_names for call in selection.tool_calls):
                    raise AgentError("mcp_tool_not_allowed", "Модель запросила не предложенный ей инструмент", 502)
                messages.append({"role": "assistant", "content": selection.content or None, "tool_calls": [
                    {"id": call.id, "type": "function", "function": {
                        "name": call.name, "arguments": json.dumps(call.arguments, ensure_ascii=False),
                    }} for call in selection.tool_calls
                ]})
                async def execute_call(call: ToolCall) -> None:
                    target = mapping.get(call.name)
                    if target is None:
                        raise AgentError("mcp_tool_not_allowed", "Модель запросила недоступный инструмент", 502)
                    server_id, tool_name = target
                    arguments = self._tool_arguments(conversation, user_index, tool_name, call.arguments)
                    try:
                        execution = await self.gateway.call_tool(server_id, tool_name, arguments)
                        data, status = execution.structured_content, "success"
                    except McpDiscoveryError as exc:
                        data, status = {"error": exc.message}, "error"
                    event = McpToolEvent(id=new_id(), agent_id=self.ID, conversation_id=conversation.id,
                                         user_index=user_index, created_at=now(), server_id=server_id,
                                         tool_name=tool_name, arguments=arguments, result=data, status=status)
                    await self.events.record(event)
                    events.append(event)
                    if status == "error":
                        raise AgentError("mcp_tool_failed", "MCP-инструмент не смог завершить операцию", 502)
                    messages.append({"role": "tool", "tool_call_id": call.id,
                                     "content": json.dumps(data, ensure_ascii=False)})

                for call in selection.tool_calls:
                    await execute_call(call)
                while followup := self._next_tool_call(conversation, user_index, events, mapping):
                    if len(events) >= self._max_tool_calls():
                        raise AgentError("mcp_too_many_calls", "Цепочка вызвала слишком много инструментов", 502)
                    if followup.name not in mapping:
                        raise AgentError("mcp_tool_not_allowed", "Цепочка запросила недоступный инструмент", 502)
                    messages.append({"role": "assistant", "content": None, "tool_calls": [{
                        "id": followup.id, "type": "function", "function": {
                            "name": followup.name,
                            "arguments": json.dumps(followup.arguments, ensure_ascii=False),
                        },
                    }]})
                    await execute_call(followup)
                final, final_run = await self._complete(messages, [], conversation.messages, text, spec, max_tokens,
                                                        conversation.id, user_index, "mcp_answer", "none")
                if final.tool_calls:
                    raise AgentError("mcp_unexpected_call", "Модель не завершила ответ после инструмента", 502)
            reply = final.content.strip()
            if not reply:
                raise AgentError("empty_response", "Модель вернула пустой ответ", 502)
            await self.store.append_message(self.ID, conversation.id, "assistant", reply)
            final_run.assistant_index = user_index + 1
            await self.accounting.record(final_run)
            return AgentResult(agent_id=self.ID, reply=reply, model=final.model, source=final.source,
                               run=final_run, additional_runs=additional_runs,
                               tool_events=events)
