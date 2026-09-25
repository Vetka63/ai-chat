"""Адаптеры DeepSeek/Mistral для стандартного цикла function calling."""

import json
import logging
import re
from typing import Any

import httpx

from agent_core.models import AgentError, ToolCall, ToolCompletion
from capabilities.token_accounting.models import TokenUsage

logger = logging.getLogger(__name__)


class ChatCompletionsToolClient:
    """Нормализует tool_calls провайдера, не выполняя инструменты сам."""

    def __init__(self, http: httpx.AsyncClient, api_key: str, provider: str, base_url: str):
        self.http, self.api_key, self.provider, self.base_url = http, api_key, provider, base_url

    async def complete_with_tools(self, messages: list[dict[str, Any]], tools: list[dict[str, Any]], *,
                                  model: str, temperature: float, max_tokens: int | None,
                                  tool_choice: str) -> ToolCompletion:
        """Передаёт инструменты API и читает либо текст, либо структурированные вызовы."""

        if not self.api_key:
            raise AgentError("llm_not_configured", "LLM API key не настроен на сервере", 503)
        payload: dict[str, Any] = {"model": model, "messages": messages, "temperature": temperature}
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = tool_choice
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens
        if self.provider == "deepseek":
            payload["thinking"] = {"type": "disabled"}
        logger.info("tool_llm_request provider=%s model=%s messages=%s tools=%s",
                    self.provider, model, len(messages), len(tools))
        try:
            response = await self.http.post(self.base_url.rstrip("/") + "/chat/completions",
                                            headers={"Authorization": f"Bearer {self.api_key}"}, json=payload)
            if response.is_error:
                logger.warning("tool_llm_error provider=%s model=%s status=%s",
                               self.provider, model, response.status_code)
                code = "rate_limit" if response.status_code == 429 else "provider_auth" if response.status_code in (401, 403) else "llm_unavailable"
                raise AgentError(code, "Провайдер не смог обработать вызов с инструментами", 502,
                                 provider_status=response.status_code)
            data = response.json()
            choice = data["choices"][0]
            message = choice["message"]
            content = message.get("content") or ""
            if isinstance(content, list):
                content = "".join(item.get("text", "") for item in content if item.get("type") == "text")
            if not isinstance(content, str):
                raise ValueError("Invalid completion content")
            calls = []
            for item in message.get("tool_calls") or []:
                function = item["function"]
                arguments = function["arguments"]
                if isinstance(arguments, str):
                    arguments = json.loads(arguments)
                if not isinstance(arguments, dict):
                    raise ValueError("Invalid tool arguments")
                calls.append(ToolCall(id=item["id"], name=function["name"], arguments=arguments))
            raw = data.get("usage")
            usage = TokenUsage(
                prompt_tokens=raw["prompt_tokens"], completion_tokens=raw["completion_tokens"],
                total_tokens=raw["total_tokens"],
                cached_tokens=raw.get("prompt_cache_hit_tokens", (raw.get("prompt_tokens_details") or {}).get("cached_tokens")),
                reasoning_tokens=(raw.get("completion_tokens_details") or {}).get("reasoning_tokens"),
            ) if raw else None
            logger.info("tool_llm_response provider=%s model=%s tool_calls=%s finish_reason=%s",
                        self.provider, model, len(calls), choice.get("finish_reason"))
            return ToolCompletion(content=content, model=data.get("model") or model,
                                  usage=usage, finish_reason=choice.get("finish_reason"), tool_calls=calls)
        except AgentError:
            raise
        except httpx.TimeoutException as exc:
            raise AgentError("provider_timeout", "Истекло время ожидания ответа модели", 504) from exc
        except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError, AttributeError) as exc:
            logger.warning("tool_llm_invalid_response provider=%s error_type=%s", self.provider, type(exc).__name__)
            raise AgentError("llm_invalid_tool_response", "Модель вернула некорректный вызов инструмента", 502) from exc


class DemoToolClient:
    """Детерминированный учебный цикл без внешних LLM-запросов."""

    @staticmethod
    def _game_title(text: str) -> str | None:
        """Извлекает простое название только для демонстрационного сценария."""

        quoted = re.search(r"[«\"]([^»\"]+)[»\"]", text)
        if quoted:
            return quoted.group(1).strip()
        match = re.search(r"\bигр(?:у|е|а)\s+([^?!.]+)", text, re.IGNORECASE)
        return match.group(1).strip() if match else None

    @staticmethod
    def _catalog_requested(text: str) -> bool:
        """В деморежиме имитирует выбор инструмента только для явного запроса каталога."""

        lower = text.casefold()
        asks_to_search = any(word in lower for word in
                             ("найд", "ищ", "поиск", "проверь", "провер", "покажи", "какие", "есть ли", "описан"))
        names_catalog = bool(re.search(r"\bкаталог\w*|\bу нас\b", lower))
        return asks_to_search and names_catalog

    @staticmethod
    def _general_reply(text: str) -> str:
        """Даёт простой честный ответ без инструмента, не изображая полноценную LLM."""

        lower = text.strip().casefold()
        if lower in {"привет", "привет!", "здравствуй"}:
            return "Привет! Чем могу помочь?"
        if "rpg" in lower or "рпг" in lower:
            return "RPG — ролевая игра, в которой игрок управляет персонажем и развивает его по ходу истории."
        if "diablo" in lower:
            return "Diablo II — известная action-RPG от Blizzard."
        if DemoToolClient._game_title(text):
            return "В демонстрационном режиме у меня нет надёжных сведений об этой конкретной игре."
        return "Это демонстрационный режим без LLM, поэтому я не могу дать содержательный ответ на открытый вопрос."

    async def complete_with_tools(self, messages, tools, *, model, temperature, max_tokens, tool_choice):
        if messages[-1]["role"] == "tool":
            payload = json.loads(messages[-1]["content"])
            if payload.get("status") == "saved" and payload.get("report_id"):
                return ToolCompletion(
                    content=f"Отчёт сохранён: {payload['file_name']}. Найдено игр: {payload['game_count']}. "
                            f"ID: {payload['report_id']}.",
                    model=f"demo/{model}", source="demo", finish_reason="stop",
                )
            games = payload.get("games", [])
            reply = "Найденные игры: " + "; ".join(f"{item['title']} — {item['description']}" for item in games) if games else "В учебном каталоге таких игр не найдено."
            return ToolCompletion(content=reply, model=f"demo/{model}", source="demo", finish_reason="stop")
        text = next(message["content"] for message in reversed(messages) if message["role"] == "user")
        report_tool = next((tool for tool in tools
                            if tool["function"]["name"] == "game_reports__search_games"), None)
        if report_tool and tool_choice == "required":
            query = "космос" if "космос" in text.casefold() else self._game_title(text) or text.strip()
            return ToolCompletion(
                content="", model=f"demo/{model}", source="demo", finish_reason="tool_calls",
                tool_calls=[ToolCall(id="demo-report-1", name=report_tool["function"]["name"],
                                     arguments={"query": query})],
            )
        if tool_choice != "required" and not self._catalog_requested(text):
            return ToolCompletion(content=self._general_reply(text), model=f"demo/{model}",
                                  source="demo", finish_reason="stop")
        if not tools:
            return ToolCompletion(content="Сейчас не могу проверить учебный каталог и не буду угадывать его содержимое.",
                                  model=f"demo/{model}", source="demo", finish_reason="stop")
        query = "космос" if "космос" in text.casefold() else self._game_title(text) or text.strip()
        return ToolCompletion(content="", model=f"demo/{model}", source="demo", finish_reason="tool_calls",
                              tool_calls=[ToolCall(id="demo-call-1", name=tools[0]["function"]["name"],
                                                   arguments={"query": query})])


class ToolProviderRouter:
    """Выбирает адаптер tool calling по каталогу моделей, не по данным браузера."""

    def __init__(self, catalog, providers: dict[str, ChatCompletionsToolClient | DemoToolClient]):
        self.catalog, self.providers = catalog, providers

    async def complete_with_tools(self, messages, tools, *, model, temperature, max_tokens, tool_choice):
        spec = self.catalog.get(model)
        return await self.providers[spec.provider].complete_with_tools(
            messages, tools, model=spec.model, temperature=temperature,
            max_tokens=max_tokens, tool_choice=tool_choice,
        )
