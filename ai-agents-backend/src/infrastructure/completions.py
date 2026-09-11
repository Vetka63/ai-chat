"""Общий HTTP-транспорт chat/completions и безопасное распознавание ошибок API."""
import logging
import re
import httpx
from agent_core.models import AgentError, Completion
from capabilities.token_accounting.models import TokenUsage

logger = logging.getLogger(__name__)


class ChatCompletionsClient:
    """Нормализует ответ провайдера; не логирует промпт или секрет."""
    def __init__(self, http: httpx.AsyncClient, api_key: str, provider: str, base_url: str):
        self.http, self.api_key = http, api_key
        self.provider, self.base_url = provider, base_url

    async def complete(self, messages, *, model, temperature, max_tokens) -> Completion:
        if not self.api_key:
            raise AgentError("llm_not_configured", "LLM API key не настроен на сервере", 503)
        payload = dict(model=model, messages=[{"role": m.role, "content": m.content} for m in messages],
                       temperature=temperature)
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens
        if self.provider == "deepseek":
            payload["thinking"] = {"type": "disabled"}
        logger.info("llm_request provider=%s model=%s messages=%s max_tokens=%s", self.provider, model, len(messages), max_tokens)
        try:
            response = await self.http.post(self.base_url.rstrip("/") + "/chat/completions",
                headers={"Authorization": f"Bearer {self.api_key}"}, json=payload)
            if response.is_error:
                body = response.text.lower()
                status = response.status_code
                code, message = "llm_unavailable", "Провайдер не смог обработать запрос"
                if status == 429:
                    code, message = "rate_limit", "Превышена квота или частота запросов провайдера"
                elif status == 413:
                    code, message = "request_too_large", "Провайдер отклонил размер HTTP-запроса"
                elif status in (401, 403):
                    code, message = "provider_auth", "Проверьте ключ и доступ к модели у провайдера"
                elif status in (400, 422) and re.search(
                    r"context.{0,60}(exceed|length|limit|window)|maximum context|too many tokens|prompt.{0,40}too long|max_tokens.{0,100}(exceed|context)", body):
                    code, message = "context_limit_exceeded", "API провайдера отклонил запрос: превышено контекстное окно модели"
                logger.warning("llm_error provider=%s model=%s status=%s code=%s", self.provider, model, status, code)
                raise AgentError(code, message, 502, provider_status=status)
            data = response.json()
            choice = data["choices"][0]
            content = choice["message"].get("content") or ""
            if isinstance(content, list):
                content = "".join(c.get("text", "") for c in content if c.get("type") == "text")
            if not isinstance(content, str):
                raise ValueError("Invalid completion content")
            raw = data.get("usage")
            usage = None
            if raw is not None:
                usage = TokenUsage(
                    prompt_tokens=raw["prompt_tokens"], completion_tokens=raw["completion_tokens"],
                    total_tokens=raw["total_tokens"],
                    cached_tokens=raw.get("prompt_cache_hit_tokens", (raw.get("prompt_tokens_details") or {}).get("cached_tokens")),
                    reasoning_tokens=(raw.get("completion_tokens_details") or {}).get("reasoning_tokens"),
                )
            result = Completion(content=content, model=data.get("model") or model,
                                usage=usage, finish_reason=choice.get("finish_reason"))
            logger.info("llm_response provider=%s model=%s finish_reason=%s content_chars=%s usage=%s",
                        self.provider, result.model, result.finish_reason, len(content), usage)
            return result
        except httpx.TimeoutException as exc:
            raise AgentError("provider_timeout", "Истекло время ожидания ответа провайдера", 504) from exc
        except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError, AttributeError) as exc:
            logger.warning("llm_invalid_response provider=%s error_type=%s", self.provider, type(exc).__name__)
            raise AgentError("llm_unavailable", "Не удалось прочитать ответ провайдера", 502) from exc
