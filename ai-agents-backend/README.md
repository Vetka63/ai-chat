# Python Agents — День 7

FastAPI-сервис с инкапсулированным агентом и постоянной историей диалога.
`DialogueAgent` применяет собственные политики, загружает сообщения через порт
`ConversationStore`, формирует полный контекст, вызывает DeepSeek и сохраняет
успешную пару `user/assistant` в SQLite.

## API

- `GET /health`
- `GET /api/v1/agents`
- `POST /api/v1/agents/{agent_id}/conversations`
- `GET /api/v1/agents/{agent_id}/conversations`
- `GET /api/v1/agents/{agent_id}/conversations/{conversation_id}`
- `DELETE /api/v1/agents/{agent_id}/conversations/{conversation_id}`
- `POST /api/v1/agents/{agent_id}/runs`

Команда запуска:

```json
{
  "conversation_id": "серверный UUID",
  "message": "Какое слово я просил запомнить?"
}
```

Клиент не может передать `history` или `system_prompt`: историю сервер достаёт
из SQLite по `conversation_id` и `agent_id`. Это исключает подмену контекста и
не позволяет одному агенту прочитать диалоги другого.

## Локальный запуск

```powershell
cd ai-agents-backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
$env:LLM_API_KEY = "ваш_ключ"
uvicorn application.main:app --app-dir src --reload --port 8082
```

По умолчанию база находится в `data/agents.sqlite3`. В Docker она размещается в
именованном volume `agents-data`, поэтому переживает restart и пересоздание
контейнера. `docker compose down -v` удаляет этот volume и всю историю.

Тесты: `pytest`.

