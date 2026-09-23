# Python MCP Games

Самостоятельный MCP-сервис на Python, лежащий рядом с `java-mcp-games` и
`ai-agents-backend`. Он не является модулем агента: агент подключается к нему
по **Streamable HTTP** через `/mcp`. Это позволяет сравнивать Python и Java
при одинаковом транспорте, инструменте и источнике данных.

Оба игровых MCP-сервера объявляют `search_games(query: str)` и читают один
`ai-games-mock-api`. Ответ инструмента: `query` и `games` (список объектов
`title`, `description`). Каталог игр находится только в mock API.

## Запуск

Из корня репозитория:

```powershell
docker compose up -d --build games-mock-api python-mcp-games java-mcp-games agents-backend agents-client
```

- Python MCP: `http://localhost:8086/mcp`.
- Java MCP: `http://localhost:8085/mcp`.
- Mock API: `http://localhost:8084/games?query=космос`.
- Клиент: `http://localhost:8083/`.

`/mcp` — адрес MCP-протокола, а не страница для открытия в браузере.
Python-агент использует внутренний Docker-адрес
`http://python-mcp-games:8080/mcp`. Сам сервис получает адрес mock API из
`GAMES_API_BASE_URL`; ключ LLM ему не нужен.

В UI выберите «Игровой MCP-агент», создайте чат только с `games-mock`
(Python), затем второй только с `java-games-mock` (Java). Задайте один вопрос
об игре и сравните карточки `search_games`. Ответы LLM могут различаться по
формулировке, но результат инструмента должен совпадать.

## Код и тесты

- `src/python_mcp_games/server.py` — HTTP MCP-сервер и инструмент.
- `tests/test_server.py` — контракт инструмента и валидация входа.
- `Dockerfile` — отдельный образ; MCP слушает порт 8080 внутри контейнера.

Если есть Python 3.12 и установлен проект с dev-зависимостями, тесты запускают
командой `python -m pytest` из этой папки.
