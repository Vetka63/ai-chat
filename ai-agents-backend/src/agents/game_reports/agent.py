"""Агент дня 19: связывает три независимых MCP-tools в проверяемую цепочку."""

import re
from uuid import NAMESPACE_URL, uuid5

from agent_core.models import AgentError, AgentInfo, ToolCall, new_id
from agents.game_reports.prompts import SYSTEM_PROMPT
from capabilities.mcp_tool_chat.agent import McpToolChatAgent


class GameReportsAgent(McpToolChatAgent):
    ID = "game_reports"
    SERVER_ID = "game-reports"
    _report_intent = re.compile(r"\b(?:отч[её]т\w*|сводк\w*)\b", re.IGNORECASE)
    _creation_intent = re.compile(
        r"\b(?:созда\w*|сдела\w*|состав\w*|собер\w*|собра\w*|"
        r"сохран\w*|запиш\w*|подготов\w*|выгруз\w*|экспорт\w*)\b",
        re.IGNORECASE,
    )

    def __init__(self, llm, store, catalog, accounting, gateway, events, default_model: str):
        super().__init__(llm, store, catalog, accounting, gateway, events, default_model,
                         system_prompt=SYSTEM_PROMPT, allowed_server_ids={self.SERVER_ID})

    @property
    def info(self) -> AgentInfo:
        return AgentInfo(
            id=self.ID, name="Отчёты по играм",
            description="Связывает поиск, обработку и сохранение через три MCP-инструмента",
            capabilities=["persistent_history", "mcp_tools", "mcp_pipeline"],
        )

    def _should_offer_tools(self, text: str) -> bool:
        return bool(self._report_intent.search(text) and self._creation_intent.search(text))

    def _initial_tools(self, text: str, tools: list[dict]) -> list[dict]:
        if not tools:
            return []
        search = [tool for tool in tools
                  if tool["function"]["name"] == self._alias(self.SERVER_ID, "search_games")]
        if len(search) != 1:
            raise AgentError("mcp_no_tools", "MCP-сервер не объявил search_games", 502)
        return search

    def _initial_tool_choice(self, text: str, tools: list[dict]) -> str:
        return "required" if tools else "none"

    def _max_initial_tool_calls(self) -> int:
        return 1

    def _max_tool_calls(self) -> int:
        return 3

    def _reply_without_tool(self, text: str, tools: list[dict]) -> str | None:
        if self._should_offer_tools(text) and not tools:
            return ("В этом сообщении инструмент сохранения недоступен. "
                    "Я не создавал и не сохранял файл отчёта.")
        return None

    def _tool_arguments(self, conversation, user_index: int, tool_name: str,
                        arguments: dict) -> dict:
        if tool_name == "search_games":
            query = arguments.get("query")
            if not isinstance(query, str) or not query.strip() or len(query.strip()) > 100:
                raise AgentError("invalid_report_query", "Модель не указала допустимую тему отчёта", 502)
            return {"query": query.strip()}
        if tool_name in {"summarize_games", "save_report"}:
            return arguments
        raise AgentError("mcp_tool_not_allowed", "Этот агент создаёт только игровые отчёты", 422)

    @staticmethod
    def _search_result(data: dict) -> dict:
        if not isinstance(data.get("query"), str) or not isinstance(data.get("games"), list):
            raise AgentError("invalid_search_result", "Поиск MCP вернул некорректные данные", 502)
        if any(not isinstance(game, dict)
               or not isinstance(game.get("title"), str) or not game["title"]
               or not isinstance(game.get("description"), str) or not game["description"]
               for game in data["games"]):
            raise AgentError("invalid_search_result", "Поиск MCP вернул некорректные игры", 502)
        return data

    @staticmethod
    def _summary_result(data: dict, search: dict) -> dict:
        if (not isinstance(data.get("query"), str)
                or data["query"] != search["query"]
                or not isinstance(data.get("game_count"), int)
                or data["game_count"] != len(search["games"])
                or not isinstance(data.get("markdown"), str)
                or not data["markdown"].strip()):
            raise AgentError("invalid_summary_result", "Обработка MCP вернула некорректный отчёт", 502)
        return data

    def _next_tool_call(self, conversation, user_index: int, events, mapping) -> ToolCall | None:
        """Агент сам строит вход B из выхода A и вход C из выхода B."""
        expected = ["search_games", "summarize_games", "save_report"]
        if len(events) > len(expected) or any(event.tool_name != expected[index]
                                              for index, event in enumerate(events)):
            raise AgentError("invalid_report_pipeline", "Порядок MCP-инструментов нарушен", 502)
        if len(events) == 1:
            search = self._search_result(events[0].result)
            return ToolCall(id=new_id(), name=self._alias(self.SERVER_ID, "summarize_games"),
                            arguments={"search_result": search})
        if len(events) == 2:
            search = self._search_result(events[0].result)
            summary = self._summary_result(events[1].result, search)
            return ToolCall(id=new_id(), name=self._alias(self.SERVER_ID, "save_report"),
                            arguments={
                                "summary_result": summary,
                                "operation_id": str(uuid5(NAMESPACE_URL,
                                                          f"{conversation.id}:{user_index}")),
                            })
        if len(events) == 3:
            summary = self._summary_result(events[1].result, self._search_result(events[0].result))
            saved = events[2].result
            if (saved.get("status") != "saved" or not isinstance(saved.get("report_id"), str)
                    or not isinstance(saved.get("file_name"), str)
                    or saved.get("report_markdown") != summary["markdown"]
                    or saved.get("game_count") != summary["game_count"]):
                raise AgentError("invalid_saved_report", "Сохранение MCP вернуло некорректный результат", 502)
        return None
