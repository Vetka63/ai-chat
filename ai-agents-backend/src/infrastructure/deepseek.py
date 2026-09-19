"""Адаптер DeepSeek и явный демонстрационный режим без платного API."""
import json

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
        if 'распределение данных по слоям памяти' in messages[0].content:
            payload = json.loads(text)
            return Completion(content=json.dumps({'proposals': [{
                'layer': 'working', 'key': 'findings', 'value': payload['user_message'],
                'reason': 'Демо: пример предложения, требуется подтверждение пользователя',
            }]}, ensure_ascii=False), model=f'demo/{model}', source='demo', finish_reason='stop')
        if 'key-value' in messages[0].content.lower():
            try:
                payload = json.loads(text)
                facts = payload.get('existing_facts') or {}
                user_message = str(payload.get('user_message') or '').strip()
            except (json.JSONDecodeError, AttributeError):
                facts, user_message = {}, text
            return Completion(
                content=json.dumps({'facts': {**facts, 'последнее_сообщение': user_message}}, ensure_ascii=False),
                model=f"demo/{model}", source="demo", finish_reason="stop",
            )
        return Completion(content=f"Демо-ответ агента на запрос: {text}", model=f"demo/{model}", source="demo", finish_reason="stop")
