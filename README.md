# AI Chat

Небольшой чат из двух приложений:

- `ai-chat-backend` — Java 21 + Spring Boot REST API;
- `ai-chat-client` — Vue 3 + Vite интерфейс;
- LLM вызывается только с backend, поэтому API-ключ не попадает в браузер;
- по умолчанию включён fallback-режим, не требующий LLM или ключей.

## Быстрый запуск через Docker Compose

Требования: Docker с плагином Docker Compose.

```bash
docker compose up --build
```

После запуска откройте <http://localhost:8080>. Backend также доступен напрямую на <http://localhost:8081>.

Проверка API без браузера:

```bash
curl -X POST http://localhost:8081/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Привет!"}'
```

Остановка:

```bash
docker compose down
```

## Подключение LLM

Backend поддерживает API, совместимый с OpenAI Chat Completions (`POST /chat/completions`). Скопируйте `.env.example` в `.env` и заполните параметры:

```dotenv
CHAT_MODE=llm
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=ваш_ключ
LLM_MODEL=gpt-4o-mini
```

Затем перезапустите контейнеры:

```bash
docker compose up --build
```

Для другого OpenAI-совместимого провайдера измените `LLM_BASE_URL` и `LLM_MODEL`. Значение `LLM_BASE_URL` должно включать `/v1`, если это требуется провайдером. Не добавляйте реальный `.env` в Git.

## Локальный запуск без Docker

Требования: Java 21, Maven 3.9+, Node.js 22+, pnpm 11+.

В первом терминале:

```bash
cd ai-chat-backend
mvn spring-boot:run
```

Во втором терминале:

```bash
cd ai-chat-client
pnpm install
pnpm dev
```

Откройте <http://localhost:5173>. Dev-сервер Vite проксирует `/api` на backend по адресу `http://localhost:8080`.

Чтобы локально включить LLM в PowerShell:

```powershell
$env:CHAT_MODE = "llm"
$env:LLM_API_KEY = "ваш_ключ"
$env:LLM_MODEL = "gpt-4o-mini"
mvn spring-boot:run
```

## Тесты и сборка

Backend:

```bash
cd ai-chat-backend
mvn test
```

Client:

```bash
cd ai-chat-client
pnpm install
pnpm test
pnpm build
```

Полная проверка Docker-образов:

```bash
docker compose build
docker compose up -d
docker compose ps
```

## API

### `POST /api/chat`

Запрос:

```json
{
  "message": "Объясни, что такое dependency injection"
}
```

Ответ:

```json
{
  "reply": "...",
  "source": "fallback"
}
```

`source` имеет значение `fallback` или `llm`. Пустые сообщения и сообщения длиннее 10 000 символов отклоняются с HTTP 400. Ошибки провайдера возвращаются как HTTP 502 без раскрытия API-ключа.

## Конфигурация backend

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `CHAT_MODE` | `fallback` | `fallback` или `llm` |
| `LLM_BASE_URL` | `https://api.openai.com/v1` | Базовый URL OpenAI-совместимого API |
| `LLM_API_KEY` | пусто | Секретный API-ключ; обязателен в режиме `llm` |
| `LLM_MODEL` | `gpt-4o-mini` | Имя модели у провайдера |
| `CORS_ALLOWED_ORIGINS` | localhost:5173, localhost:8080 | Разрешённые origin через запятую |

## Развёртывание на VPS

1. Установите Docker и Docker Compose.
2. Скопируйте репозиторий на VPS.
3. Создайте `.env` рядом с `docker-compose.yml`.
4. Выполните `docker compose up -d --build`.
5. Для публичного доступа поставьте перед портом `8080` reverse proxy (Caddy, Nginx или Traefik) и включите HTTPS.

Порт backend `8081` нужен только для диагностики. На публичном VPS его лучше удалить из секции `ports` или ограничить firewall: браузер работает через `/api`, который Nginx client-контейнера проксирует во внутреннюю Docker-сеть.
