# Java MCP Games

Самостоятельный MCP-сервер на Java 21, Spring Boot 4.1 и Spring AI 2.0. Он
лежит рядом с `ai-agents-backend`, а не внутри него. Это альтернативная
реализация игрового инструмента Дня 17 для сравнения с Python MCP-сервером.
Оба сервера читают **один** каталог через отдельный `ai-games-mock-api`;
данные игр здесь не дублируются.

## Схема

`ai-agents-client → ai-agents-backend (MCP client) → java-mcp-games (MCP over HTTP) → games-mock-api`

У Java-сервера один инструмент: `search_games(query: string)`. Параметр
обязателен. Ответ содержит `query` и `games` — список объектов с `title` и
`description`. Неизвестная игра возвращает пустой список, как в Python-версии.
Оба MCP-сервера работают по stateless Streamable HTTP на `/mcp`.

## Запуск и проверка

Из корня репозитория:

```powershell
docker compose up -d --build games-mock-api python-mcp-games java-mcp-games agents-backend agents-client
```

Адреса: Java MCP — `http://localhost:8085/mcp`, его health —
`http://localhost:8085/actuator/health`, mock API — `http://localhost:8084/games?query=космос`,
чат — `http://localhost:8083/`. `/mcp` — не обычная веб-страница: отправляйте
туда MCP-запросы через SDK. В Docker Python-агент обращается к
`http://java-mcp-games:8080/mcp` по внутренней сети.

В UI выберите «Игровой MCP-агент» и создайте два отдельных чата с одним
подключением в каждом: `games-mock` (Python) и `java-games-mock` (Java).
Отправьте одинаковый вопрос, например «Какая игра в каталоге про космос?».
В карточке MCP-вызова видно, какой сервер выполнил `search_games` и какие
данные он вернул. Формулировка ответа LLM может отличаться, но каталог и
структура результата инструмента должны совпадать.

Java-тесты выполняются при `docker compose build java-mcp-games` (Maven
`package` без пропуска тестов). Для отдельного запуска через локальный Maven:

```powershell
cd java-mcp-games
mvn test
```

`GAMES_API_BASE_URL` указывает на mock API. Ключ LLM Java-сервису не нужен:
модель вызывает Python-агент, а Java-сервис выполняет только инструмент.

## Код

- `GamesTools` — MCP-регистрация `search_games` и его схемы входа/выхода.
- `GamesCatalogClient` — валидация `query` и HTTP-запрос к каталогу.
- `Game` и `GamesResult` — контракт результата.
- `RestClientConfig` — HTTP-клиент для mock API.
- `application.yml` — имя MCP-сервера, stateless HTTP, порт и адрес каталога.

При добавлении другого каталога достаточно поменять источник данных и
контракт инструмента в этом проекте; Python-агент знает только MCP-протокол
и список разрешённых серверов.
