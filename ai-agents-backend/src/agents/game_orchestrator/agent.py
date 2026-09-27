"""Модель выбирает каждый следующий шаг; бэкенд проверяет и выполняет MCP-вызовы."""

import asyncio
import hashlib
import json
import re
from uuid import NAMESPACE_URL, uuid5

from agent_core.models import AgentCommand, AgentError, AgentInfo, AgentResult, McpToolEvent, new_id, now
from agents.game_orchestrator.prompts import SYSTEM_PROMPT
from capabilities.mcp_discovery.service import McpDiscoveryError
from capabilities.mcp_orchestration.models import Artifact
from capabilities.mcp_tool_chat.agent import McpToolChatAgent


def definition(name: str, description: str, properties: dict, required: list[str]) -> dict:
    return {"type": "function", "function": {
        "name": name, "description": description,
        "parameters": {"type": "object", "properties": properties, "required": required,
                       "additionalProperties": False},
    }}


STRING = {"type": "string", "minLength": 1}
ID_LIST = {"type": "array", "items": STRING, "minItems": 1, "maxItems": 20}


class GameOrchestratorAgent(McpToolChatAgent):
    ID = "game_orchestrator"
    JAVA = "java-games-mock"
    PYTHON = "game-reports"
    GO = "go-report-store"
    ALLOWED = {JAVA, PYTHON, GO}
    _save_verb = re.compile(r"сохран\w*|запиш\w*|скача\w*|экспорт\w*", re.IGNORECASE)
    _report_noun = re.compile(r"отч[её]т\w*|файл\w*|markdown|\bmd\b", re.IGNORECASE)

    def __init__(self, llm, store, catalog, accounting, gateway, events, artifacts, default_model: str):
        super().__init__(llm, store, catalog, accounting, gateway, events, default_model,
                         system_prompt=SYSTEM_PROMPT, allowed_server_ids=self.ALLOWED)
        self.artifacts = artifacts

    @property
    def info(self) -> AgentInfo:
        return AgentInfo(
            id=self.ID, name="MCP-оркестратор · День 20",
            description="Строит подборки и отчёты через Java, Python и Go MCP",
            capabilities=["persistent_history", "mcp_tools", "mcp_orchestration"],
        )

    @staticmethod
    def _workspace_context(items: list[Artifact]) -> str:
        return json.dumps([{
            "id": item.id, "type": item.kind, "title": item.title,
            "sources": item.source_ids,
            "game_count": len(item.payload.get("games", [])) if item.kind in {"search", "collection"} else None,
        } for item in items[-40:]], ensure_ascii=False)

    async def _definitions(self, selected: list[str], items: list[Artifact], save_requested: bool) -> list[dict]:
        """Показывает модели только инструменты подключённых серверов с выполненными предпосылками."""
        discovered = {}
        for server_id in selected:
            result = await self.gateway.discover(server_id)
            discovered[server_id] = {tool.name for tool in result.tools}
        tools = []
        if "search_games" in discovered.get(self.JAVA, set()):
            tools.append(definition("java_games__search_games",
                "Search the educational game catalog. Use for factual catalog lookup; query is a short topic or exact title.",
                {"query": STRING}, ["query"]))
        if any(item.kind == "search" for item in items):
            tools.append(definition("workspace__save_collection",
                "Select specific games by zero-based indices from one saved search result and persist a named collection.",
                {"search_id": STRING, "title": STRING,
                 "game_indices": {"type": "array", "items": {"type": "integer", "minimum": 0},
                                  "minItems": 1, "maxItems": 20}},
                ["search_id", "title", "game_indices"]))
        if "summarize_games" in discovered.get(self.PYTHON, set()) and any(item.kind == "collection" for item in items):
            tools.append(definition("python_analysis__summarize_collection",
                "Create and persist a summary of exactly one named game collection.",
                {"collection_id": STRING}, ["collection_id"]))
        if "compose_report" in discovered.get(self.PYTHON, set()) and any(item.kind == "summary" for item in items):
            tools.append(definition("python_analysis__compose_report",
                "Create a report draft from exactly the selected stored summaries; unrelated searches are excluded.",
                {"title": STRING, "summary_ids": ID_LIST}, ["title", "summary_ids"]))
        if (save_requested and "save_report" in discovered.get(self.GO, set())
                and any(item.kind == "draft" for item in items)):
            tools.append(definition("go_reports__save_report",
                "Persist a completed report draft to Markdown. Only when the user explicitly asks to save or download.",
                {"draft_id": STRING}, ["draft_id"]))
        return tools

    async def _save_artifact(self, conversation, kind: str, title: str, payload: dict,
                             sources: list[str], user_index: int) -> Artifact:
        item = Artifact(id=new_id(), agent_id=self.ID, conversation_id=conversation.id,
                        kind=kind, title=title, payload=payload, source_ids=sources,
                        user_index=user_index, created_at=now())
        return await self.artifacts.save(item)

    async def _mcp(self, conversation, user_index: int, server_id: str,
                   tool_name: str, args: dict, events: list[McpToolEvent]) -> dict:
        try:
            result = await self.gateway.call_tool(server_id, tool_name, args)
            data, status = result.structured_content, "success"
        except McpDiscoveryError as exc:
            data, status = {"error": exc.message}, "error"
        event = McpToolEvent(id=new_id(), agent_id=self.ID, conversation_id=conversation.id,
                             user_index=user_index, created_at=now(), server_id=server_id,
                             tool_name=tool_name, arguments=args, result=data, status=status)
        await self.events.record(event)
        events.append(event)
        if status == "error":
            raise AgentError("mcp_tool_failed", f"Сбой инструмента {tool_name}; следующие шаги не выполнены", 502)
        return data

    @staticmethod
    def _text(value, label: str, maximum: int = 200) -> str:
        if not isinstance(value, str) or not value.strip() or len(value.strip()) > maximum:
            raise AgentError("invalid_tool_arguments", f"Неверный параметр {label}", 422)
        return value.strip()

    async def _execute(self, name: str, args: dict, conversation, user_index: int,
                       events: list[McpToolEvent], save_requested: bool) -> dict:
        """Не даёт модели подменить результаты предыдущих инструментов аргументами."""
        if name == "java_games__search_games":
            query = self._text(args.get("query"), "query", 100)
            result = await self._mcp(conversation, user_index, self.JAVA, "search_games",
                                     {"query": query}, events)
            games = result.get("games")
            if not isinstance(games, list) or not isinstance(result.get("query"), str) or any(
                not isinstance(game, dict) or not isinstance(game.get("title"), str)
                or not isinstance(game.get("description"), str) for game in games
            ):
                raise AgentError("invalid_search_result", "Каталог вернул некорректные данные игр", 502)
            item = await self._save_artifact(conversation, "search", f"Поиск: {query}", result, [], user_index)
            return {"artifact_id": item.id, "query": result["query"],
                    "games": [{"index": index, **game} for index, game in enumerate(games)]}

        if name == "workspace__save_collection":
            source = await self.artifacts.get(self.ID, conversation.id,
                                              self._text(args.get("search_id"), "search_id"), "search")
            title = self._text(args.get("title"), "title")
            indices = args.get("game_indices")
            games = source.payload["games"]
            if (not isinstance(indices, list) or not 1 <= len(indices) <= 20
                    or any(type(i) is not int or i < 0 or i >= len(games) for i in indices)
                    or len(indices) != len(set(indices))):
                raise AgentError("invalid_collection_selection", "Выберите существующие игры без повторов", 422)
            selected = [games[index] for index in indices]
            item = await self._save_artifact(conversation, "collection", title,
                {"query": source.payload["query"], "games": selected}, [source.id], user_index)
            return {"artifact_id": item.id, "title": title, "games": selected}

        if name == "python_analysis__summarize_collection":
            collection = await self.artifacts.get(self.ID, conversation.id,
                self._text(args.get("collection_id"), "collection_id"), "collection")
            search = {"query": collection.title, "games": collection.payload["games"]}
            result = await self._mcp(conversation, user_index, self.PYTHON, "summarize_games",
                                     {"search_result": search}, events)
            if (result.get("query") != collection.title or result.get("game_count") != len(search["games"])
                    or not isinstance(result.get("markdown"), str) or not result["markdown"].strip()):
                raise AgentError("invalid_summary_result", "MCP вернул некорректную сводку", 502)
            item = await self._save_artifact(conversation, "summary",
                f"Сводка: {collection.title}", result, [collection.id], user_index)
            return {"artifact_id": item.id, "title": item.title, **result}

        if name == "python_analysis__compose_report":
            title = self._text(args.get("title"), "title")
            ids = args.get("summary_ids")
            if (not isinstance(ids, list) or not 1 <= len(ids) <= 20
                    or len(ids) != len(set(ids)) or any(not isinstance(i, str) for i in ids)):
                raise AgentError("invalid_summary_selection", "Укажите конкретные сводки без повторов", 422)
            sources = [await self.artifacts.get(self.ID, conversation.id, item_id, "summary") for item_id in ids]
            payload = {"title": title, "summaries": [{
                "id": item.id, "title": item.title, "markdown": item.payload["markdown"],
            } for item in sources]}
            result = await self._mcp(conversation, user_index, self.PYTHON, "compose_report", payload, events)
            if (result.get("title") != title or result.get("source_summary_ids") != ids
                    or not isinstance(result.get("markdown"), str) or not result["markdown"].strip()):
                raise AgentError("invalid_report_draft", "MCP вернул некорректный черновик отчёта", 502)
            item = await self._save_artifact(conversation, "draft", title, result, ids, user_index)
            return {"artifact_id": item.id, **result}

        if name == "go_reports__save_report":
            if not save_requested:
                raise AgentError("save_not_requested", "Сохранение требует явной просьбы пользователя", 422)
            draft = await self.artifacts.get(self.ID, conversation.id,
                self._text(args.get("draft_id"), "draft_id"), "draft")
            operation_id = str(uuid5(NAMESPACE_URL, f"{conversation.id}:{draft.id}"))
            result = await self._mcp(conversation, user_index, self.GO, "save_report",
                {"report_draft": draft.payload, "operation_id": operation_id}, events)
            digest = hashlib.sha256(draft.payload["markdown"].encode("utf-8")).hexdigest()
            if (result.get("status") != "saved" or result.get("content_sha256") != digest
                    or result.get("source_summary_ids") != draft.source_ids
                    or not isinstance(result.get("report_id"), str)
                    or not isinstance(result.get("file_name"), str)):
                raise AgentError("invalid_save_result", "Go MCP не подтвердил сохранение отчёта", 502)
            saved = {**result, "report_markdown": draft.payload["markdown"]}
            item = await self._save_artifact(conversation, "report", draft.title, saved, [draft.id], user_index)
            return {"artifact_id": item.id, **result}
        raise AgentError("mcp_tool_not_allowed", "Модель выбрала недоступный инструмент", 422)

    async def run(self, command: AgentCommand) -> AgentResult:
        text = command.message.strip()
        if not text or len(text) > 10000:
            raise AgentError("invalid_message", "Сообщение должно содержать от 1 до 10000 символов")
        async with self._locks.setdefault(command.conversation_id, asyncio.Lock()):
            conversation = await self.store.get(self.ID, command.conversation_id)
            selected = command.mcp_server_ids if command.mcp_server_ids is not None else conversation.mcp_server_ids
            if len(selected) != len(set(selected)):
                raise AgentError("invalid_mcp_servers", "MCP-серверы не должны повторяться")
            for server_id in selected:
                self._check_server(server_id)
            model_id = command.model_id or conversation.selected_model_id or self.default_model
            spec = self.catalog.get(model_id)
            max_tokens = (command.max_output_tokens if "max_output_tokens" in command.model_fields_set
                          else conversation.max_output_tokens)
            if max_tokens is not None and max_tokens > spec.max_output_tokens:
                raise AgentError("invalid_output_limit", "Лимит ответа превышает максимум модели")
            await self.store.select_model(self.ID, conversation.id, model_id)
            await self.store.configure_output(self.ID, conversation.id, max_tokens)
            await self.store.append_message(self.ID, conversation.id, "user", text, mcp_server_ids=selected)
            user_index = len(conversation.messages)
            items = await self.artifacts.list(self.ID, conversation.id)
            messages = [{"role": "system", "content": self.system_prompt},
                        {"role": "system", "content": "Артефакты этого чата: " + self._workspace_context(items)}]
            messages.extend({"role": item.role, "content": item.content} for item in conversation.messages)
            messages.append({"role": "user", "content": text})
            save_requested = bool(self._save_verb.search(text) and self._report_noun.search(text))
            events: list[McpToolEvent] = []
            runs = []
            final = None
            for step in range(9):
                items = await self.artifacts.list(self.ID, conversation.id)
                tools = await self._definitions(selected, items, save_requested)
                answer, run = await self._complete(messages, tools, conversation.messages, text,
                    spec, max_tokens, conversation.id, user_index,
                    "mcp_orchestration_step" if tools else "mcp_answer",
                    "auto" if tools else "none")
                runs.append(run)
                if not answer.tool_calls:
                    final = answer
                    break
                if len(answer.tool_calls) != 1:
                    raise AgentError("mcp_multiple_calls", "В одном шаге разрешён один инструмент", 502)
                call = answer.tool_calls[0]
                if call.name not in {tool["function"]["name"] for tool in tools}:
                    raise AgentError("mcp_tool_not_allowed", "Модель запросила недоступный инструмент", 502)
                messages.append({"role": "assistant", "content": answer.content or None, "tool_calls": [{
                    "id": call.id, "type": "function", "function": {
                        "name": call.name, "arguments": json.dumps(call.arguments, ensure_ascii=False),
                    },
                }]})
                output = await self._execute(call.name, call.arguments, conversation, user_index,
                                             events, save_requested)
                messages.append({"role": "tool", "tool_call_id": call.id,
                                 "content": json.dumps(output, ensure_ascii=False)})
            if final is None:
                raise AgentError("mcp_step_limit", "Агент превысил допустимое число шагов", 502)
            reply = final.content.strip()
            if not reply:
                raise AgentError("empty_response", "Модель вернула пустой ответ", 502)
            if save_requested and not any(event.server_id == self.GO and event.status == "success"
                                           for event in events):
                reply = "Отчёт в этом сообщении не сохранён. Уточните нужные сводки или подключите необходимые MCP-серверы."
            await self.store.append_message(self.ID, conversation.id, "assistant", reply)
            runs[-1].assistant_index = user_index + 1
            await self.accounting.record(runs[-1])
            return AgentResult(agent_id=self.ID, reply=reply, model=final.model, source=final.source,
                               run=runs[-1], additional_runs=runs[:-1], tool_events=events,
                               artifacts=await self.artifacts.list(self.ID, conversation.id))
