# Python Agents — День 6

Отдельный FastAPI-сервис с первым инкапсулированным агентом. Он принимает
сообщение, сам применяет свои политики и валидаторы, формирует системный промпт,
вызывает DeepSeek через абстракцию `LlmClient` и возвращает ответ.

## API

- `GET /health`
- `GET /api/v1/agents`
- `POST /api/v1/agents/{agent_id}/runs` с телом `{"message":"Привет"}`

Поля `history`, `system_prompt` и параметры провайдера публичный контракт не
принимает. Это намеренное ограничение Дня 6: сохранение контекста появится только
в задании Дня 7.

## Локальный запуск

```powershell
cd ai-agents-backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
$env:LLM_API_KEY = "ваш_ключ"
uvicorn application.main:app --app-dir src --reload --port 8082
```

Для явного запуска без внешнего API задайте `PY_AGENT_MODE=demo`. По умолчанию
используется настоящий DeepSeek API.

Тесты: `pytest`.

