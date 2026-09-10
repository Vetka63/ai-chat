"""HTTP-адаптер DeepSeek, реализующий общий порт LlmClient."""

import logging

import httpx

from agent_core.models import AgentError, Completion, Message

logger = logging.getLogger(__name__)


class DeepSeekClient:
    """Отправляет подготовленные агентом сообщения в OpenAI-совместимый API."""

    def __init__(self, http: httpx.AsyncClient, api_key: str):
        self.http = http
        self.api_key = api_key

    async def complete(
        self,
        messages: list[Message],
        *,
        model: str,
        temperature: float,
        max_tokens: int,
    ) -> Completion:
        if not self.api_key:
            raise AgentError("llm_not_configured", "LLM API key не настроен на сервере", 503)

        payload = {
            "model": model,
            "messages": [message.model_dump() for message in messages],
            # У V4 thinking включён по умолчанию. Для обычного чат-агента он может
            # израсходовать весь max_tokens до формирования видимого ответа.
            "thinking": {"type": "disabled"},
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        logger.info("llm_request provider=deepseek model=%s messages=%s", model, len(messages))
        try:
            response = await self.http.post(
                "chat/completions",
                headers={"Authorization": f"Bearer {self.api_key}"},
                json=payload,
            )
            response.raise_for_status()
            data = response.json()
            choice = data["choices"][0]
            message = choice["message"]
            content = message.get("content") or ""
            if not isinstance(content, str):
                raise TypeError("LLM content must be a string or null")
            returned_model = data.get("model") or model
            reasoning = message.get("reasoning_content") or ""
            usage = data.get("usage") or {}
            logger.info(
                "llm_response provider=deepseek model=%s finish_reason=%s "
                "content_chars=%s reasoning_chars=%s prompt_tokens=%s completion_tokens=%s",
                returned_model,
                choice.get("finish_reason"),
                len(content),
                len(reasoning) if isinstance(reasoning, str) else 0,
                usage.get("prompt_tokens"),
                usage.get("completion_tokens"),
            )
        except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError) as exc:
            logger.exception("llm_error provider=deepseek model=%s", model)
            raise AgentError("llm_unavailable", "Не удалось получить ответ от LLM", 502) from exc

        return Completion(content=content, model=returned_model)


class DemoClient:
    """Явный локальный режим для разработки без имитации реального LLM usage."""

    async def complete(
        self,
        messages: list[Message],
        *,
        model: str,
        temperature: float,
        max_tokens: int,
    ) -> Completion:
        text = next(message.content for message in reversed(messages) if message.role == "user")
        return Completion(
            content=f"Демо-ответ агента на запрос: {text}",
            model=f"demo/{model}",
            source="demo",
        )
