# Python Agents — День 12

Добавлены профили и типизированные предпочтения алгоритмического наставника.
Профиль выбирается при создании задачи, редактируется явно и подключается к
каждому её запросу. [Руководство и сравнение Дня 12](docs/day12-guide.md).

Добавлен отдельный **Алгоритмический наставник** с краткосрочной, рабочей и
долговременной памятью. Записи сохраняются явно, предложения LLM требуют
подтверждения. [Руководство и сценарий сдачи Дня 11](docs/day11-guide.md).
В UI выбирайте наставника; диалоговый агент Дней 6–10 продолжает работать отдельно.

Добавлены три выбираемые при создании чата стратегии: Sliding Window, Sticky Facts
и Branching. После создания стратегия неизменяема. [Руководство Дня 10](docs/day10-guide.md) описывает UI, API, единый
сценарий проверки и ограничения. Полная история и summary Дня 9 сохранены.

Добавлены выбор модели, подключаемый учёт токенов и расходов. [Полное руководство Дня 8](docs/day8-guide.md).

FastAPI-сервис с инкапсулированным агентом и постоянной историей диалога.
`DialogueAgent` применяет собственные политики, загружает сообщения через порт
`ConversationStore`, формирует полный контекст, вызывает DeepSeek и сохраняет
сообщения `user/assistant` в SQLite. Вопрос сохраняется до внешнего вызова, поэтому
он остаётся в истории даже при сбое LLM; ответ сохраняется только после проверки.

## API

- `GET /health`
- `GET /api/v1/agents`
- `POST /api/v1/agents/{agent_id}/conversations`
- `GET /api/v1/agents/{agent_id}/conversations`
- `GET /api/v1/agents/{agent_id}/conversations/{conversation_id}`
- `DELETE /api/v1/agents/{agent_id}/conversations/{conversation_id}`
- `POST /api/v1/agents/{agent_id}/runs`
- `PATCH /api/v1/agents/{agent_id}/conversations/{conversation_id}/context` — защитный `409`, настройки неизменяемы
- `POST /api/v1/agents/{agent_id}/conversations/{conversation_id}/checkpoints`
- `GET /api/v1/agents/{agent_id}/conversations/{conversation_id}/checkpoints`
- `POST /api/v1/agents/{agent_id}/checkpoints/{checkpoint_id}/branches`

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

