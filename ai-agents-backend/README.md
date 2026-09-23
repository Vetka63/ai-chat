# Python Agents — День 17

Новый «Игровой MCP-агент» вызывает MCP-инструмент поиска через отдельный
[Python HTTP MCP-сервис](../python-mcp-games/README.md) по
отдельному mock API игр, получает результат и показывает его в UI вместе со
следом вызова. [Сценарий проверки и архитектура Дня 17](docs/day17-guide.md).

Добавлен отдельный MCP-раздел: официальный Python MCP SDK подключается к
локальному учебному серверу по `stdio` и возвращает его инструменты через API.
Чат и LLM в этом флоу не участвуют. [Пошаговая проверка Дня 16](docs/day16-guide.md).

Для Дня 17 добавлен независимый [Java MCP-сервер игр](../java-mcp-games/README.md):
тот же `search_games` и общий mock API доступны в отдельном чате через
`java-games-mock` по Streamable HTTP. [Сценарий сравнения](docs/day17-guide.md).

Алгоритмический наставник использует контролируемый жизненный цикл
`planning → execution → validation → done`. План, решение и проверка связаны
версиями, запрещённые переходы одинаково блокируются через UI и прямой API.
[Руководство Дня 15](docs/day15-v2-guide.md).

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
- `GET /api/v1/mcp/servers`
- `POST /api/v1/mcp/servers/local-demo/discover`
- `POST /api/v1/mcp/servers/games-mock/discover`
- `POST /api/v1/mcp/servers/java-games-mock/discover`
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

## Документация по заданиям

- [День 15: архитектура контролируемой Task State Machine](docs/day15-state-machine-architecture.md)
- [День 15 v2: сценарий проверки через UI](docs/day15-v2-guide.md)

