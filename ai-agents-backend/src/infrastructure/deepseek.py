"""Адаптер DeepSeek и явный демонстрационный режим без платного API."""
from agent_core.models import Completion
from infrastructure.completions import ChatCompletionsClient


class DeepSeekClient(ChatCompletionsClient):
    """Использует настройки DeepSeek поверх общего транспорта."""
    def __init__(self, http, api_key, base_url="https://api.deepseek.com"):
        super().__init__(http, api_key, "deepseek", base_url)


class DemoClient:
    """Возвращает локальный ответ с неизвестным usage, не выдумывая биллинг."""
    async def complete(self, messages, *, model, temperature, max_tokens):
        text = next(m.content for m in reversed(messages) if m.role == "user")
        return Completion(content=f"Демо-ответ агента на запрос: {text}", model=f"demo/{model}", source="demo", finish_reason="stop")
