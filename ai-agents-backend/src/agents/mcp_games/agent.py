"""Агент: выбор инструмента моделью → вызов MCP → итоговый ответ модели."""

import asyncio
import json
import re
from time import perf_counter

from agent_core.models import AgentCommand, AgentError, AgentInfo, AgentResult, Message, now, new_id
from agents.mcp_games.prompts import SYSTEM_PROMPT
from capabilities.mcp_discovery.models import McpToolEvent
from capabilities.mcp_discovery.service import McpDiscoveryError
from capabilities.token_accounting.models import ContextEstimate, RunRecord


class McpGamesAgent:
    """Изолирует чат с играми и допускает только разрешённые для текущего хода MCP-серверы."""

    ID = "mcp_games"

    def __init__(self, llm, store, catalog, accounting, gateway, events, default_model: str):
        self.llm, self.store, self.catalog = llm, store, catalog
        self.accounting, self.gateway, self.events = accounting, gateway, events
        self.default_model = default_model
        self._locks: dict[str, asyncio.Lock] = {}

    @property
    def info(self) -> AgentInfo:
        return AgentInfo(id=self.ID, name="Игровой MCP-агент",
                         description="Отвечает по отдельному каталогу игр через MCP-инструмент",
                         capabilities=["persistent_history", "mcp_tools"])

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
            self.gateway.chat_server(server_id)
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
            self.gateway.chat_server(server_id)
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
            system_tokens=estimator.count_text(SYSTEM_PROMPT),
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
                self.gateway.chat_server(server_id)
            model_id = command.model_id or conversation.selected_model_id or self.default_model
            spec = self.catalog.get(model_id)
            max_tokens = command.max_output_tokens if "max_output_tokens" in command.model_fields_set else conversation.max_output_tokens
            if max_tokens is not None and max_tokens > spec.max_output_tokens:
                raise AgentError("invalid_output_limit", "Лимит ответа превышает максимум модели")
            await self.store.select_model(self.ID, conversation.id, model_id)
            await self.store.configure_output(self.ID, conversation.id, max_tokens)
            await self.store.append_message(self.ID, conversation.id, "user", text, mcp_server_ids=selected_ids)
            tools, mapping = await self._tools(selected_ids)
            system_prompt = SYSTEM_PROMPT if tools else (SYSTEM_PROMPT +
                "\nДля текущего сообщения MCP-инструменты отключены. Не утверждай, что проверил каталог; "
                "если нужны данные каталога, попроси включить MCP.")
            messages = [{"role": "system", "content": system_prompt}]
            messages.extend({"role": item.role, "content": item.content} for item in conversation.messages)
            messages.append({"role": "user", "content": text})
            user_index = len(conversation.messages)
            first, first_run = await self._complete(messages, tools, conversation.messages, text, spec, max_tokens,
                                                    conversation.id, user_index,
                                                    "mcp_selection" if tools else "mcp_answer",
                                                    "auto" if tools else "none")
            events = []
            final, final_run = first, first_run
            if first.tool_calls:
                if len(first.tool_calls) > 3:
                    raise AgentError("mcp_too_many_calls", "Модель запросила слишком много инструментов", 502)
                messages.append({"role": "assistant", "content": first.content or None, "tool_calls": [
                    {"id": call.id, "type": "function", "function": {
                        "name": call.name, "arguments": json.dumps(call.arguments, ensure_ascii=False),
                    }} for call in first.tool_calls
                ]})
                for call in first.tool_calls:
                    target = mapping.get(call.name)
                    if target is None:
                        raise AgentError("mcp_tool_not_allowed", "Модель запросила недоступный инструмент", 502)
                    server_id, tool_name = target
                    try:
                        execution = await self.gateway.call_tool(server_id, tool_name, call.arguments)
                        data, status = execution.structured_content, "success"
                    except McpDiscoveryError as exc:
                        data, status = {"error": exc.message}, "error"
                    event = McpToolEvent(id=new_id(), agent_id=self.ID, conversation_id=conversation.id,
                                         user_index=user_index, created_at=now(), server_id=server_id,
                                         tool_name=tool_name, arguments=call.arguments, result=data, status=status)
                    await self.events.record(event)
                    events.append(event)
                    if status == "error":
                        raise AgentError("mcp_tool_failed", "Не удалось получить данные из MCP-каталога", 502)
                    messages.append({"role": "tool", "tool_call_id": call.id,
                                     "content": json.dumps(data, ensure_ascii=False)})
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
                               run=final_run, additional_runs=[first_run] if first_run is not final_run else [],
                               tool_events=events)
